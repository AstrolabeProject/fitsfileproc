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
 *   Last Modified: Remove mutable class variables. Reload mutable fieldsInfo per FITS file.
 *                  Refactor to add FITS header info directly to fieldsInfo. Cleanups.
 */
class JwstProcessor implements IFitsFileProcessor {
  static final Logger log = LogManager.getLogger(JwstProcessor.class.getName());

  /** String which defines a comment line in the JWST resource files. */
  static final String COMMENT_MARKER = '#'

  /** String which defines the line containing column_names in the JWST resource files. */
  static final String COLUMN_NAME_MARKER = '_COLUMN_NAMES_'

  /** String which marks a field with no default value or a value to be calculated. */
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


  /** Public constructor taking a map of configuration settings. */
  public JwstProcessor (configuration) {
    log.trace("(JwstProcessor.ctor): config=${configuration}")
    config = configuration                  // save incoming settings in global variable
    DEBUG = configuration.DEBUG ?: false
    VERBOSE = configuration.VERBOSE ?: false

    // load the FITS field name aliases from a given file path or a default resource path.
    fitsAliases = loadAliases(config.aliasFile)
    // if (DEBUG)                              // REMOVE LATER
    //   fitsAliases.each { entry -> println("${entry.key}=${entry.value}") }
  }


  /** Process the single given file. */
  int processAFile (File aFile) {
    log.trace("(JwstProcessor.processAFile): aFile=${aFile}")

    Fits fits = readFitsFile(aFile)         // make FITS object from given FITS file
    if (!fits)                              // if unable to open/read FITS file
      return 0                              // then skip this file

    // Map defining information for fields processed by this processor.
    // Loads the field information from a given file or a default resource path.
    Map fieldsInfo = loadFieldsInfo(config.fieldsFile)

    Header header = fits.getHDU(0).getHeader() // get the header from the primary HDU
    Map headerFields = getHeaderFields(fits)   // get a map of all FITS headers and value strings
    // if (DEBUG) {                               // REMOVE LATER
    //   println("HDR FIELDS(${headerFields.size()}): ${headerFields}")
    //   // headerFields.each { key, val -> println("${key}: ${val}") }
    // }

    addInfoFromFitsHeaders(headerFields, fieldsInfo) // get FITS header field keys and values
    addValuesForFields(fieldsInfo)          // fetch values from FITS file headers

    if (DEBUG) {                            // REMOVE LATER
      fieldsInfo.each { entry -> println("${entry.key}=${entry.value}") }
    }

    computeValuesForFields(fieldsInfo)      // compute missing values
    addDefaultValuesForFields(fieldsInfo)   // add defaults for missing values, if possible
    ensureRequiredFields(fieldsInfo)        // check for all required fields

    return 1                                // successfully processed one more file
  }

  private void addDefaultValuesForFields (Map fieldsInfo) {
    log.trace("(JwstProcessor.addDefaultValuesForFields): fieldsInfo=${fieldsInfo}")
    fieldsInfo.each { key, fieldInfo ->
      // TODO:
      // get the default value
      // if (default value is not NO_DEFAULT_VALUE marker) {
      //   convert default value to correct type
      //   add value to fieldsInfo map
      // }

      // addDefaultValueForAField(fieldInfo)
      // if (DEBUG)                                // REMOVE LATER
      //   println("aDVFF: FIELDINFO=${fieldInfo}") // REMOVE LATER
    }
  }

  private void computeValuesForFields (Map fieldsInfo) {
    // TODO: IMPLEMENT LATER
    //   if value is computed: try keyword dispatch to calculation routine

    // NOTE: substitute 1347.0 for 0.0 t_exptime value (from Eiichi Egami 20190626)
    // NOTE: value for instrument_name = NIRCam + MODULE value
    // NOTE: Ask Eiichi about o_ucd: what is being measured? photo.flux.density? others?
  }

  private void ensureRequiredFields (Map fieldsInfo) {
    // TODO: IMPLEMENT LATER
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
   * Try to fetch a value of the correct type for each field in the given field
   * information map. If found, the field value is added back to the field information.
   */
  private void addValuesForFields (Map fieldsInfo) {
    log.trace("(JwstProcessor.findValuesForFields): fieldsInfo=${fieldsInfo}")
    fieldsInfo.each { key, fieldInfo ->
      addValueForAField(fieldInfo)
      // if (DEBUG)                                // REMOVE LATER
      //   println("aVFF: FIELDINFO=${fieldInfo}") // REMOVE LATER
    }
  }

  /**
   * Get a value for the field with the given field information. Try to convert
   * the header value string to the proper datatype.
   */
  private boolean addValueForAField (Map fieldInfo) {
    log.trace("(JwstProcessor.addValueForAField): fieldInfo=${fieldInfo}")

    def value = null                        // return variable for extracted value
    def valueStr = fieldInfo['hdrValueStr'] // string value for header keyword
    def datatype = fieldInfo['datatype']    // data type for the value
    if ((valueStr == null) || !datatype)    // sanity check: need at least value and datatype
      return false                          // exit out now

    try {
      switch (datatype) {                   // dispatch conversions on the datatype
        case 'integer':
          value = valueStr as Integer
          break
        case 'double':
          value = valueStr as Double
          break
        case 'string':
          value = valueStr
          break
        default:
          def fitsKey = fieldInfo['hdrKey']       // header key from FITS file
          def msg = "Unknown datatype '${datatype}' for field '${fitsKey}'. Ignoring bad field value."
          logError('JwstProcessor.addValueForAField', msg)
          value = null
          break
      }
    } catch (NumberFormatException nfe) {
      def fitsKey = fieldInfo['hdrKey']       // header key from FITS file
      def msg = "Unable to convert value '${valueStr}' for field '${fitsKey}' to '${datatype}'. Ignoring bad field value."
      logError('JwstProcessor.addValueForAField', msg)
      value = null
    }

    if (value != null) {                    // if we extracted a value
      fieldInfo['value'] = value            // then save extracted value in the field info map
      return true                           // signal success
    }
    return false                            // else some problem: signal failure
  }


  // def getFitsFields (Fits fits) {
  //   log.trace("(JwstProcessor.getFitsFields): fits=${fits}")
  //   Map hdrMap = [:]

  //   BasicHDU bhdu = fits.getHDU(0)
  //   Header header = bhdu.getHeader()
  //   hdrMap = [
  //     'NAXIS1'   : header.getIntValue('NAXIS1'), // FITS required keyword
  //     'NAXIS2'   : header.getIntValue('NAXIS2'), // FITS required keyword
  //     'AUTHOR'   : bhdu.getAuthor(),
  //     'obs_creation_date' : bhdu.getCreationDate(),
  //     'EQUINOX'  : bhdu.getEquinox(),
  //     'INSTRUME' : bhdu.getInstrument(),
  //     'OBJECT'   : bhdu.getObject(),
  //     'DATE-OBS' : bhdu.getObservationDate(),
  //     'OBSERVER' : bhdu.getObserver(),
  //     'ORIGIN'   : bhdu.getOrigin(),
  //     'TELESCOP' : bhdu.getInstrument()
  //   ]

  //   return hdrMap.findAll { it.value != null } // filter out null values
  // }

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

}

// TODO:
// for all keys read {
//   map key to ObsCore name
//   get datatype from ObsCore key in fields map
//   read value of datatype using original key
//   if value missing: try to get the default value
//   if value is computed: try dispatch to calculation routine
//   if value still missing?: fill in null?
// }
