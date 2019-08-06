package edu.arizona.astrolabe.ffp

import java.io.*
import java.util.zip.GZIPInputStream
import nom.tam.fits.*
import org.apache.logging.log4j.*

/**
 * Astrolabe JWST-specific FITS file processor class.
 *   This class implements JWST-specific FITS file processing methods.
 *
 *   Written by: Tom Hicks. 7/28/2019.
 *   Last Modified: Remove o_ucd: provided in defaults.
 */
class JwstProcessor implements IFitsFileProcessor {
  static final Logger log = LogManager.getLogger(JwstProcessor.class.getName());

  /** String which defines a comment line in the JWST resource files. */
  static final String COMMENT_MARKER = '#'

  /** String which defines the line containing column_names in the JWST resource files. */
  static final String COLUMN_NAME_MARKER = '_COLUMN_NAMES_'

  /** String which marks a field with "no default value" or a value to be calculated. */
  static final String NO_DEFAULT_VALUE = '*'

  /** String which defines a "not yet implemented" line in the JWST resource files. */
  static final String NOP_ENTRY_KEY = '_NOP_'

  /** Default resource file for header keyword aliases. */
  static final String DEFAULT_ALIASES_FILEPATH = '/jwst-aliases.txt'

  /** Default resource file for header field information. */
  static final String DEFAULT_FIELDS_FILEPATH = '/jwst-fields.txt'


  /** Debug setting: when true, show internal information for debugging. */
  private boolean DEBUG   = false

  /** Verbose setting: when true, show extra information about program operation. */
  private boolean VERBOSE = false

  /** Configuration parameters given to this class in the constructor. */
  private Map config

  /** List of column names in the header field information file (with default values). */
  private List fieldInfoColumnNames = [ 'obsCoreKey', 'datatype', 'required', 'default' ]

  /** Mappping of FITS header keywords to ObsCore keywords.
      Read from a given external file or a default internal resource file. */
  private Map fitsAliases

  /** An instance of IInformationOutputter for outputting the processed information. */
  private IInformationOutputter infoOutputter


  /** Public constructor taking a map of configuration settings. */
  public JwstProcessor (configuration) {
    log.trace("(JwstProcessor.ctor): config=${configuration}")
    config = configuration                  // save incoming settings in global variable
    DEBUG = configuration.DEBUG ?: false
    VERBOSE = configuration.VERBOSE ?: false

    // load the FITS field name aliases from a given file path or a default resource path.
    fitsAliases = loadAliases(config.aliasFile)
    // if (DEBUG)                              // REMOVE LATER
    //   fitsAliases.each { entry -> System.err.println("${entry.key}=${entry.value}") }
    infoOutputter = new InformationOutputter(configuration)
  }


  /** Process the single given file. */
  int processAFile (File aFile) {
    log.trace("(JwstProcessor.processAFile): aFile=${aFile}")

    Fits fits = readFitsFile(aFile)         // make FITS object from given FITS file
    if (!fits)                              // if unable to open/read FITS file
      return 0                              // then skip this file

    // Map defining information for fields processed by this processor.
    // Loads the field information from a given file or a default resource path.
    // NOTE: need to reload this for each file as it will be mutated for each file:
    Map fieldsInfo = loadFieldsInfo(config.fieldsFile)

    Header header = fits.getHDU(0).getHeader() // get the header from the primary HDU
    Map headerFields = getHeaderFields(fits)   // get a map of all FITS headers and value strings
    // if (DEBUG) {                               // REMOVE LATER
    //   System.err.println("HDR FIELDS(${headerFields.size()}): ${headerFields}")
    //   // headerFields.each { key, val -> System.err.println("${key}: ${val}") }
    // }

    try {
      // add information about the input file that is being processed
      addFileInformation(aFile, fieldsInfo)

      // add header field keys and string values from the FITS file
      addInfoFromFitsHeaders(headerFields, fieldsInfo)

      // convert the header field string values, where possible
      addValuesForFields(fieldsInfo)

      // add defaults for missing values, if possible
      addDefaultValuesForFields(fieldsInfo)

      // try to compute values for computable fields which are still missing values
      computeValuesForFields(headerFields, fieldsInfo)
      if (DEBUG) {                          // REMOVE LATER
        fieldsInfo.each { entry -> System.err.println("${entry.key}=${entry.value}") }
      }

      // do some checks for required fields
      ensureRequiredFields(fieldsInfo)

      // output the extracted field information
      infoOutputter.outputInformation(fieldsInfo)
    }
    catch (AbortFileProcessingException afpx) {
      def msg =
        "Failed to process file '${aFile.getAbsolutePath()}'. Error message was:\n${afpx.message}"
      logError('JwstProcessor.processAFile', msg)
      return 0                              // signal failure to process the file
    }

    return 1                                // successfully processed one more file
  }


  /**
   * Try to instantiate a default value of the correct type for each field in the given
   * fields map which does not already have a value.
   */
  private void addDefaultValuesForFields (Map fieldsInfo) {
    log.trace("(JwstProcessor.addDefaultValuesForFields): fieldsInfo=${fieldsInfo}")
    fieldsInfo.each { key, fieldInfo ->
      if (!hasValue(fieldInfo)) {           // do not replace existing values
        addDefaultValueForAField(fieldInfo)
      }
    }
  }

  /**
   * Try to instantiate a default value of the correct type for the given field
   * information map which does not already have a value. If found, the value is
   * added back to the field information. Ignores field information maps which
   * have a "no default value" marker.
   */
  private void addDefaultValueForAField (Map fieldInfo) {
    log.trace("(JwstProcessor.addDefaultValueForAField): fieldInfo=${fieldInfo}")
    if (hasValue(fieldInfo))                // do not replace existing values
      return                                // exit out now

    def defaultStr = fieldInfo['default']   // get default string value
    def datatype = fieldInfo['datatype']    // data type for the value
    if ( (defaultStr == null) ||            // sanity check: need at least value and datatype
         !datatype ||
         (defaultStr == NO_DEFAULT_VALUE) ) // ignore fields with a "no default value" marker
      return                                // exit out now

    def value = null                        // variable for extracted value
    try {
      value = stringToValue(defaultStr, datatype) // extract value of given type
    }
    catch (NumberFormatException nfe) {     // MUST catch before parent exception below
      def obsCoreKey = fieldInfo['obsCoreKey']
      def msg = "Unable to convert default value '${defaultStr}' for field '${obsCoreKey}' to '${datatype}'. Field value not set."
      logError('JwstProcessor.addDefaultValueForAField', msg)
      value = null
    }
    catch (IllegalArgumentException iax) {
      def obsCoreKey = fieldInfo['obsCoreKey']
      def msg = "Unknown datatype '${datatype}' for field '${obsCoreKey}'. Field value not set."
      logError('JwstProcessor.addDefaultValueForAField', msg)
      value = null
    }

    if (value != null)                      // if we extracted a value
      fieldInfo['value'] = value            // then save extracted value in the field info map
  }


  /**
   * Add information about the given input file to the field information map keyed with
   * a special keyword that is shared with other modules.
   */
  private void addFileInformation (File aFile, Map fieldsInfo) {
    log.trace("(JwstProcessor.addFileInformation): aFile=${aFile}, fieldsInfo=${fieldsInfo}")
    def fileInfoKey = IInformationOutputter.FILE_INFO_KEYWORD
    def fileInfoMap =[ 'fileName': aFile.getName(),
                       'filePath': aFile.getAbsolutePath(),
                       'fileSize': aFile.length() ]
    fieldsInfo[fileInfoKey] = fileInfoMap
  }

  /**
   * For the given map of FITS file header fields, find the corresponding ObsCore keyword,
   * if any, and lookup the field information for that key. If found, add the corresponding
   * FITS file header keyword and value string.
   */
  private void addInfoFromFitsHeaders (Map headerFields, Map fieldsInfo) {
    log.trace("(JwstProcessor.addInfoFromFitsHeaders): headerFields=${headerFields}")
    headerFields.each { hdrKey, hdrValueStr ->
      def obsCoreKey = getObsCoreKeyFromAlias(hdrKey) // map header key to ObsCore key
      if (obsCoreKey) {                               // if found alias mapping
        def fieldInfo = fieldsInfo[obsCoreKey]
        if (fieldInfo) {                    // if we have this ObsCore field, add header info
          fieldInfo << [ "hdrKey": hdrKey, "hdrValueStr": hdrValueStr ]
        }
      }
    }
  }


  /**
   * Try to fetch a value of the correct type for each field in the given fields map.
   * If found, a field value is added back to the corresponding field information.
   */
  private void addValuesForFields (Map fieldsInfo) {
    log.trace("(JwstProcessor.findValuesForFields): fieldsInfo=${fieldsInfo}")
    fieldsInfo.each { key, fieldInfo ->
      addValueForAField(fieldInfo)
    }
  }

  /**
   * Try to create a value of the correct type for the given field information map.
   * Tries to convert the header value string to the proper datatype. If successful,
   * the value is added back to the field information.
   */
  private void addValueForAField (Map fieldInfo) {
    log.trace("(JwstProcessor.addValueForAField): fieldInfo=${fieldInfo}")

    def valueStr = fieldInfo['hdrValueStr'] // string value for header keyword
    def datatype = fieldInfo['datatype']    // data type for the value
    if ((valueStr == null) || !datatype)    // sanity check: need at least value and datatype
      return                                // exit out now

    def value = null                        // variable for extracted value
    try {
      value = stringToValue(valueStr, datatype) // extract value of given type
    }
    catch (NumberFormatException nfe) {     // MUST catch before parent exception below
      def fitsKey = fieldInfo['hdrKey']     // header key from FITS file
      def msg = "Unable to convert value '${valueStr}' for field '${fitsKey}' to '${datatype}'. Ignoring bad field value."
      logError('JwstProcessor.addValueForAField', msg)
      value = null
    }
    catch (IllegalArgumentException iax) {
      def fitsKey = fieldInfo['hdrKey']     // header key from FITS file
      def msg = "Unknown datatype '${datatype}' for field '${fitsKey}'. Ignoring bad field value."
      logError('JwstProcessor.addValueForAField', msg)
      value = null
    }

    if (value != null)                      // if we extracted a value
      fieldInfo['value'] = value            // then save extracted value in the field info map
  }


  /**
   * Try to compute a value of the correct type for each field in the given
   * field maps which does not already have a value.
   */
  private void computeValuesForFields (Map headerFields, Map fieldsInfo) {
    log.trace("(JwstProcessor.computeValuesForFields): headerFields=${headerFields}, fieldsInfo=${fieldsInfo}")

    ////////////////////////////////////////////////////////////////////////////////
    // NOTE: SPECIAL CASE: correct the t_exptime zero value
    //       with a default of 1347.0 per Eiichi Egami 20190626.
    //       Remove this code when the t_exptime field gets real values in the future.
    def tExptime = getValueFor('t_exptime', fieldsInfo)
    if (tExptime == 0.0)
      fieldsInfo['t_exptime']['value'] = 1347.0 as Double
    ////////////////////////////////////////////////////////////////////////////////

    fieldsInfo.each { key, fieldInfo ->
      if (!hasValue(fieldInfo)) {           // do not replace existing values
        computeValueForAField(fieldInfo, headerFields, fieldsInfo)
      }
    }
  }

  /**
   * Try to compute a value of the correct type for the given field information
   * map which does not already have a value. If successful, the value is added back
   * to the field information. The map containing all fields is also passed to this method
   * to enable calculations based on the values of other fields.
   */
  private void computeValueForAField (Map fieldInfo, Map headerFields, Map fieldsInfo) {
    log.trace(
      "(JwstProcessor.computeValueForAField): fI=${fieldInfo}, hF=${headerFields}, fsI=${fieldsInfo}")

    if (hasValue(fieldInfo))                // do not replace existing values
      return                                // exit out now

    def obsCoreKey = fieldInfo['obsCoreKey']
    switch(obsCoreKey) {
      case ['s_ra', 's_dec']:               // coordinate fields extracted from the file
        handleWcsCoords(headerFields, fieldsInfo)
        break
      case 'access_estsize':                // estimated size is the size of the file
        def fileInfo = fieldsInfo[IInformationOutputter.FILE_INFO_KEYWORD]
        if (fileInfo && fileInfo['fileSize'])
          fieldInfo['value'] = fileInfo['fileSize']
        break
      case 'instrument_name':               // NIRCam + MODULE value
        def module = getValueFor('nircam_module', fieldsInfo)
        String instName = (module != null) ? "NIRCam-${module}" : "NIRCam"
        fieldInfo['value'] = instName
        break
    }
  }


  private void ensureRequiredFields (Map fieldsInfo) {
    log.trace("(JwstProcessor.ensureRequiredFields): fieldsInfo=${fieldsInfo}")
    // TODO: IMPLEMENT MORE LATER?
    fieldsInfo.each { key, fieldInfo ->
      def obsCoreKey = fieldInfo['obsCoreKey']
      if (obsCoreKey && !hasValue(fieldInfo)) { // find ObsCore fields which still have no value
        def reqFld = fieldInfo['required'] ? 'Required' : 'Optional'
        def msg = "${reqFld} field '${obsCoreKey}' still does not have a value."
        if (VERBOSE || DEBUG)
          logWarning('JwstProcessor.ensureRequiredFields', msg, false)
      }
    }
  }


  /**
   * Return a List of all non-comment (key/value pair) keywords
   * in the header of the first HDU of the given FITS file.
   */
  private List getHeaderKeys (Fits fits) {
    log.trace("(JwstProcessor.getHeaderKeys): fits=${fits}")
    Header header = fits.getHDU(0).getHeader()
    return header.iterator().findAll{it.isKeyValuePair()}.collect{it.getKey()}
  }

  /**
   * Return a Map of all non-comment (key/value pair) keywords and their values
   * in the header of the first HDU of the given FITS file.
   */
  private Map getHeaderFields (Fits fits) {
    log.trace("(JwstProcessor.getHeaderFields): fits=${fits}")
    Header header = fits.getHDU(0).getHeader()
    return header.iterator().findAll{it.isKeyValuePair()}.collectEntries {
      [ (it.getKey()) : it.getValue() ] }
  }

  /** Return the ObsCore keyword for the given FITS header keyword, or null if none found. */
  private String getObsCoreKeyFromAlias (hdrKey) {
    log.trace("(JwstProcessor.getObsCoreKeyFromAlias): hdrKey=${hdrKey}")
    return fitsAliases.get(hdrKey)
  }

  /**
   * Return the current value for the named field in the given fields map, or null if
   *  the named field is not present or if it does not have a current value.
   */
  private def getValueFor (String whichField, Map fieldsInfo) {
    def fld = fieldsInfo.get(whichField)
    return ((fld != null) ? fld.get('value') : null)
  }


  /**
   * Extract the WCS coordinates for the current file. Sets both s_ra and s_dec fields
   * simultaneously when given either one. This method assumes that neither s_ra nor
   * s_dec fields have a value yet and it will overwrite current values for both
   * s_ra and s_dec if that assumption is not valid.
   *
   * NOTE: Currently we only handle Tangent Plane (gnomonic) projections, as specified
   *       by the FITS CTYPE1 header field. All other projections cause processing of
   *       the current file to be aborted.
   */
  private void handleWcsCoords (Map headerFields, Map fieldsInfo) {
    def ctype1 = headerFields['CTYPE1']
    def ctype2 = headerFields['CTYPE2']
    def crval1 = headerFields['CRVAL1'] as Double
    def crval2 = headerFields['CRVAL2'] as Double

    def raInfo  = fieldsInfo['s_ra']        // get s_ra entry
    def decInfo = fieldsInfo['s_dec']       // get s_dec entry

    if (ctype1 && ctype2 && crval1 && crval2 && raInfo && decInfo) {  // sanity check all vars
      if (ctype1 == 'RA---TAN') {           // if CRVAL1 has the RA value
        raInfo['value'] = crval1            // put CRVAL1 value into s_ra
        decInfo['value'] = crval2           // put CRVAL2 value into s_dec
      }
      else if (ctype1 == 'DEC--TAN') {      // if CRVAL1 has the DEC value
        decInfo['value'] = crval1           // put CRVAL1 value into s_dec
        raInfo['value'] = crval2            // put CRVAL2 value into s_ra
      }
      else {                                // else cannot handle the projection type
        throw new AbortFileProcessingException(
          "This program currently only handles Tangent Plane projection and cannot process files with CTYPE1 of '${ctype1}'.")
      }
    }
  }


  /** Return true if the given field info map has a data value, else return false. */
  private boolean hasValue (Map fieldInfo) {
    return (fieldInfo['value'] != null)
  }

  /** Locate the aliases file, load the aliases, and return them. */
  private Map loadAliases (File aliasFile) {
    log.trace("(JwstProcessor.loadAliases): aliasFile=${aliasFile}")
    def aliasCnt = 0
    def aliases = [:]
    def aliasStream
    def aliasFilepath = DEFAULT_ALIASES_FILEPATH

    if (aliasFile) {                          // if given external aliases file, use it
      aliasStream = new FileInputStream(aliasFile)
      aliasFilepath = aliasFile.getAbsolutePath()
    }
    else                                    // else fallback to default resource
      aliasStream = this.getClass().getResourceAsStream(DEFAULT_ALIASES_FILEPATH);

    if (VERBOSE)
      log.info("(JwstProcessor.loadAliases): Reading aliases from: ${aliasFilepath}")

    def inSR = new InputStreamReader(aliasStream, 'UTF8')
    inSR.eachLine { line ->
      if (line.trim().isEmpty() ||
          line.startsWith(COMMENT_MARKER) ||
          line.startsWith(NOP_ENTRY_KEY) ||
          line.startsWith(COLUMN_NAME_MARKER)) {
        // ignore empty lines, comment lines, not yet implemented lines, and column name lines
      }
      else {                                // line passes basic checks
        def fields = line.split(',')
        if (fields.size() == 2) {
          aliases.put(fields[0].trim(), fields[1].trim())
          aliasCnt += 1
        }
      }
    }

    if (VERBOSE)
      log.info("(JwstProcessor.loadAliases): Read ${aliasCnt} field name aliases.")

    return aliases
  }


  /**
   * Read the file containing information about the fields processed by this program,
   * and return the fields in a map. Loads the field information from the given file
   * or a default resource path, if no file given.
   */
  private Map loadFieldsInfo (File fieldsFile=null) {
    log.trace("(JwstProcessor.loadFieldsInfo): fieldsFile=${fieldsFile}")
    def recCnt = 0
    def fields = [:]
    def fieldStream
    def fieldsFilepath = DEFAULT_FIELDS_FILEPATH

    if (fieldsFile) {                       // if given external fields file, use it
      fieldStream = new FileInputStream(fieldsFile)
      fieldsFilepath = fieldsFile.getAbsolutePath()
    }
    else                                    // else fallback to default resource
      fieldStream = this.getClass().getResourceAsStream(DEFAULT_FIELDS_FILEPATH);

    if (VERBOSE)
      log.info("(JwstProcessor.loadFieldsInfo): Reading field information from: ${fieldsFilepath}")

    def numInfoFields = fieldInfoColumnNames.size() // to avoid recomputation in loop

    def inSR = new InputStreamReader(fieldStream, 'UTF8')
    inSR.eachLine { line ->
      if (line.trim().isEmpty() || line.startsWith(COMMENT_MARKER)) {
        // ignore empty lines and comment lines
      }
      else if (line.startsWith(COLUMN_NAME_MARKER)) { // line is a column name (header) line
        def flds = line.split(',').collect{it.trim()}
        if (flds.size() > 2) {              // must be at least two column names
          fieldInfoColumnNames = flds[1..-1] // drop column_name_marker (first field)
        }
      }
      else {                                // assume line is a data line
        def flds = line.split(',').collect{it.trim()}
        if (flds && (flds.size() == numInfoFields)) {
          def infoMap = [fieldInfoColumnNames, flds].transpose().collectEntries()
          fields.put(flds[0], infoMap)      // store all fields keyed by first field
          recCnt += 1
        }
      }
    }

    if (VERBOSE)
      log.info("(JwstProcessor.loadFieldsInfo): Read ${recCnt} field information records.")

    return fields
  }


  /**
   * Log the given error message from the named method. Output the error to
   * the standard error stream if the final argument is true (the default).
   */
  private void logError (String fromWhere, String msg, boolean toSysErr=true) {
    log.error("(${fromWhere}): ${msg}")
    if (toSysErr)
      System.err.println("ERROR: (${fromWhere}): ${msg}")
  }

  /**
   * Log the given warning message from the named method. Output the warning to
   * the standard error stream if the final argument is true (the default).
   */
  private void logWarning (String fromWhere, String msg, boolean toSysErr=true) {
    log.warn("(${fromWhere}): ${msg}")
    if (toSysErr)
      System.err.println("WARNING: (${fromWhere}): ${msg}")
  }


  /**
   * Open, read, and return a Fits object from the given File, which is assumed to be
   * pointing to a valid, readable FITS file.
   * Returns the new Fits object, or null if problems encountered.
   */
  private Fits readFitsFile (File aFile) {
    log.trace("(JwstProcessor.readFitsFile): aFile=${aFile}")
    Fits fits = null
    if (aFile.getName().endsWith('.gz'))
      fits = new Fits(new FileInputStream(aFile))
    else
      fits = new Fits(aFile)

    try {
      fits.read()                             // read the data into FITS object
    }
    catch (Exception iox) {
      def msg = "Invalid FITS Header encountered in file '${aFile.getAbsolutePath()}'. File skipped."
      logError('JwstProcessor.readFitsFile', msg)
      return null                           // signal unable to open/read FITS file
    }

    return fits                             // everything OK: return Fits object
  }


  /**
   * Convert the given string value to the given datatype. Allowed datatype values
   * are limited: "date", "double", "integer", "string".
   */
  private def stringToValue (String valueStr, String datatype) {
    log.trace("(JwstProcessor.stringToValue): valueStr='${valueStr}', datatype='${datatype}'")

    if ((valueStr == null) || !datatype)    // sanity check: need at least value and datatype
      return null                           // exit out now

    def value = null                        // return variable for extracted value
    try {                                   // dispatch conversions on the datatype
      if (datatype == 'integer')
        value = valueStr as Integer
      else if (datatype == 'double')
        value = valueStr as Double
      else if (datatype == 'string')
        value = valueStr
      else if (datatype == 'date')
        value = new FitsDate(valueStr)      // FITS date = ISO-8601 w/o the trailing Z
      else {
        throw new IllegalArgumentException(
          "Unknown datatype '${datatype}' specified for conversion.")
      }
    } catch (NumberFormatException nfe) {
      throw new NumberFormatException(
        "Unable to convert value '${valueStr}' to '${datatype}'.")
    }
  }

}
