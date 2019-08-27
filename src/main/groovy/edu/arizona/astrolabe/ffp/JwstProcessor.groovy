package edu.arizona.astrolabe.ffp

import java.io.*
import java.util.zip.GZIPInputStream
import org.apache.logging.log4j.*

import nom.tam.fits.*
import ca.nrc.cadc.wcs.*
import ca.nrc.cadc.wcs.Transform.Result

/**
 * Astrolabe JWST-specific FITS file processor class.
 *   This class implements JWST-specific FITS file processing methods.
 *
 *   Written by: Tom Hicks. 7/28/2019.
 *   Last Modified: Calculate target name based on filename. Safeguard coord transform.
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

  /** Spatial resolutions for NIRCam filters, keyed by filter name. */
  static final Map FILTER_RESOLUTIONS = [
    'F070W': 0.030, 'F090W': 0.034,  'F115W': 0.040,  'F140M': 0.048, 'F150W': 0.050,
    'F162M': 0.055, 'F164N': 0.056,  'F150W2': 0.046, 'F182M': 0.062, 'F187N': 0.064,
    'F200W': 0.066, 'F210M': 0.071,  'F212N': 0.072,  'F250M': 0.084, 'F277W': 0.091,
    'F300M': 0.100, 'F322W2': 0.097, 'F323N': 0.108,  'F335M': 0.111, 'F356W': 0.115,
    'F360M': 0.120, 'F405N': 0.136,  'F410M': 0.137,  'F430M': 0.145, 'F444W': 0.145,
    'F460M': 0.155, 'F466N': 0.158,  'F470N': 0.160,  'F480M': 0.162
  ]

  static final Map PIXTYPE_TABLE = [
    "8": "byte", "16": "short", "32": "int", "64": "long", "-32": "float", "-64": "double"
  ]


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

  /** An instance of the field information factory. */
  private FieldsInfoFactory fieldsInfoFactory


  /** Public constructor taking a map of configuration settings. */
  public JwstProcessor (configuration) {
    log.trace("(JwstProcessor.ctor): config=${configuration}")
    config = configuration                  // save incoming settings in global variable
    DEBUG = configuration.DEBUG ?: false
    VERBOSE = configuration.VERBOSE ?: false

    // load the FITS field name aliases from a given file path or a default resource path.
    fitsAliases = loadAliases(config.aliasFile)
    // if (DEBUG)
    //   fitsAliases.each { entry -> System.err.println("${entry.key}=${entry.value}") }
    infoOutputter = new InformationOutputter(configuration)

    // add some processor-specific settings to the configuration file
    config << [ 'fieldInfoColumnNames': ['obsCoreKey', 'datatype', 'required', 'default'] ]
    config << [ 'defaultFieldsFilepath': '/jwst-fields.txt' ]

    // instantiate a factory to load field information
    fieldsInfoFactory = new FieldsInfoFactory(config)
  }


  /** Process the single given file. */
  public int processAFile (File aFile) {
    log.trace("(JwstProcessor.processAFile): aFile=${aFile}")

    Fits fits = readFitsFile(aFile)         // make FITS object from given FITS file
    if (!fits)                              // if unable to open/read FITS file
      return 0                              // then skip this file

    if (VERBOSE)
      log.info("(JwstProcessor.processAFile): Processing FITS file '${aFile.getAbsolutePath()}'")

    // Data structure defining information for fields processed by this processor.
    // Loads the field information from a given file or a default resource path.
    // NOTE: need to reload this for each file as it will be mutated for each file:
    FieldsInfo fieldsInfo = fieldsInfoFactory.loadFieldsInfo(config.fieldsFile)

    Header header = fits.getHDU(0).getHeader() // get the header from the primary HDU
    Map headerFields = getHeaderFields(fits)   // get a map of all FITS headers and value strings
    // if (DEBUG) {
    //   System.err.println("HDR FIELDS(${headerFields.size()})")
    //   headerFields.each { key, val ->
    //     System.err.println("${key}: ${val} ${val.getClass().getName()}")
    //   }
    // }

    // add information about the input file that is being processed
    addFileInformation(aFile, fieldsInfo)

    // calculate the scale for each image
    calcScale(headerFields, fieldsInfo)

    try {
      // add header field keys and string values from the FITS file
      addInfoFromFitsHeaders(headerFields, fieldsInfo)

      // convert the header field string values, where possible
      convertHeaderValues(fieldsInfo)

      // add defaults for missing values, if possible
      addDefaultValuesForFields(fieldsInfo)

      // calculate the image corners and spatial limits
      calcCorners(headerFields, fieldsInfo)

      // try to compute values for computable fields which are still missing values
      computeValuesForFields(headerFields, fieldsInfo)
      // if (DEBUG) {
      //   fieldsInfo.each { entry -> System.err.println("${entry.key}=${entry.value}") }
      // }

      // do some checks for required fields
      ensureRequiredFields(fieldsInfo)

      // output the extracted field information
      infoOutputter.outputInformation(fieldsInfo)
    }
    catch (AbortFileProcessingException afpx) {
      def msg =
        "Failed to process file '${aFile.getAbsolutePath()}'. Error message was:\n${afpx.message}"
      Utils.logError('JwstProcessor.processAFile', msg)
      return 0                              // signal failure to process the file
    }

    return 1                                // successfully processed one more file
  }


  /**
   * Try to instantiate a default value of the correct type for each field in the given
   * fields map which does not already have a value.
   */
  private void addDefaultValuesForFields (FieldsInfo fieldsInfo) {
    log.trace("(JwstProcessor.addDefaultValuesForFields): fieldsInfo=${fieldsInfo}")
    fieldsInfo.each { key, fieldInfo ->
      if (!fieldInfo.hasValue()) {          // do not replace existing values
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
  private void addDefaultValueForAField (FieldInfo fieldInfo) {
    log.trace("(JwstProcessor.addDefaultValueForAField): fieldInfo=${fieldInfo}")
    if (fieldInfo.hasValue())               // do not replace existing values
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
      Utils.logError('JwstProcessor.addDefaultValueForAField', msg)
      value = null
    }
    catch (IllegalArgumentException iax) {
      def obsCoreKey = fieldInfo['obsCoreKey']
      def msg = "Unknown datatype '${datatype}' for field '${obsCoreKey}'. Field value not set."
      Utils.logError('JwstProcessor.addDefaultValueForAField', msg)
      value = null
    }

    if (value != null)                      // if we extracted a value
      fieldInfo.setValue(value)             // then save extracted value in the field info map
  }


  /**
   * Add information about the given input file to the field information map keyed with
   * a special keyword that is shared with other modules.
   */
  private void addFileInformation (File aFile, FieldsInfo fieldsInfo) {
    log.trace("(JwstProcessor.addFileInformation): aFile=${aFile}, fieldsInfo=${fieldsInfo}")

    def fnameInfo = fieldsInfo.get('file_name')
    if (fnameInfo != null)
      fnameInfo.setValue(aFile.getName())

    def fpathInfo = fieldsInfo.get('file_path')
    if (fpathInfo != null)
      fpathInfo.setValue(aFile.getAbsolutePath())

    // estimated size is the size of the file
    def fsizeInfo = fieldsInfo.get('access_estsize')
    if (fsizeInfo != null)
      fsizeInfo.setValue(aFile.length())
  }


  /**
   * For the given map of FITS file header fields, find the corresponding ObsCore keyword,
   * if any, and lookup the field information for that key. If found, add the corresponding
   * FITS file header keyword and value string.
   */
  private void addInfoFromFitsHeaders (Map headerFields, FieldsInfo fieldsInfo) {
    log.trace("(JwstProcessor.addInfoFromFitsHeaders): headerFields=${headerFields}")
    headerFields.each { hdrKey, hdrValueStr ->
      def obsCoreKey = getObsCoreKeyFromAlias(hdrKey) // map header key to ObsCore key
      if (obsCoreKey) {                               // if found alias mapping
        def fieldInfo = fieldsInfo.get(obsCoreKey)
        if (fieldInfo) {                    // if we have this ObsCore field, add header info
          fieldInfo << [ "hdrKey": hdrKey, "hdrValueStr": hdrValueStr ]
        }
      }
    }
  }


  /**
   * Calculate the corner points and spatial limits for the current image, given the FITS
   * file header fields, and the field information map. The calculated corners and limits
   * are stored back into the field information map.
   */
  private void calcCorners (Map headerFields, FieldsInfo fieldsInfo) {
    log.trace("(JwstProcessor.calcCorners): headerFields=${headerFields}, fieldsInfo=${fieldsInfo}")

    if (['im_ra1','im_dec1', 'im_ra2','im_dec2',  // sanity check all needed fields
         'im_ra3','im_dec3', 'im_ra4','im_dec4'].collect{fieldsInfo[it]}.every{it})
    {
      WCSKeywords wcs = getWcsKeys(headerFields) // WCS lib needs its own key/value data structure
      Transform trans = new Transform(wcs)       // create a transform from the keywords

      Double naxis1 = wcs.getDoubleValue('NAXIS1') // max RA pixel index
      Double naxis2 = wcs.getDoubleValue('NAXIS2') // max DEC pixel index

      setCornerField(fieldsInfo, 'im_ra1', 'im_dec1',
                     transformPix2Sky(trans, 1.0, 1.0))       // LowerLeft

      setCornerField(fieldsInfo, 'im_ra2', 'im_dec2',
                     transformPix2Sky(trans, 1.0, naxis2))    // UpperLeft

      setCornerField(fieldsInfo, 'im_ra3', 'im_dec3',
                     transformPix2Sky(trans, naxis1, naxis2)) // UpperRight

      setCornerField(fieldsInfo, 'im_ra4', 'im_dec4',
                     transformPix2Sky(trans, naxis1, 1.0))    // LowerRight

      // now use the corners to calculate the min/max spatial limits of the image
      calcSpatialLimits(headerFields, fieldsInfo)
    }
  }


  /**
   * Calculate the scale (arcsec/pixel) for the current image using the given
   * FITS file header and field information.
   *  scale = 3600.0 * sqrt((cd1_1**2 + cd2_1**2 + cd1_2**2 + cd2_2**2) / 2.0)
   */
  private void calcScale (Map headerFields, FieldsInfo fieldsInfo) {
    log.trace("(JwstProcessor.calcScale): headerFields=${headerFields}, fieldsInfo=${fieldsInfo}")
    def cd1_1 = headerFields['CD1_1'] as Double
    def cd1_2 = headerFields['CD1_2'] as Double
    def cd2_1 = headerFields['CD2_1'] as Double
    def cd2_2 = headerFields['CD2_2'] as Double

    if ( (cd1_1 != null) && (cd1_2 != null) &&  // sanity check all vars
         (cd2_1 != null) && (cd2_2 != null) )
    {
      Double scale = 3600.0 * Math.sqrt( (Math.pow(cd1_1, 2.0) +
                                          Math.pow(cd1_2, 2.0) +
                                          Math.pow(cd2_1, 2.0) +
                                          Math.pow(cd2_2, 2.0) / 2.0) )
      def fieldInfo = fieldsInfo.get('im_scale')
      if (fieldInfo)
        fieldInfo.setValue(scale)
    }
  }


  /**
   * Calculate the value string for the ObsCore im_pixeltype field based on the value
   * of the FITS BITPIX keyword.
   */
  private void calcPixtype (Map headerFields, FieldsInfo fieldsInfo) {
    log.trace("(JwstProcessor.calcPixtype): headerFields=${headerFields}, fieldsInfo=${fieldsInfo}")
    def bitpix = headerFields['BITPIX']
    def ptInfo = fieldsInfo['im_pixtype']
    if (bitpix && ptInfo) {
      ptInfo['value'] = PIXTYPE_TABLE.get(bitpix, 'UNKNOWN')
    }
  }


  /**
   * Calculate the min/max of the RA and DEC axes. This method must be called after the
   * image corners are computed, since it relies on those values.
   */
  private void calcSpatialLimits (Map headerFields, FieldsInfo fieldsInfo) {
    log.trace("(JwstProcessor.calcSpatialLimits): headerFields=${headerFields}, fieldsInfo=${fieldsInfo}")
    def lo1Info  = fieldsInfo.get('spat_lolimit1')
    def hi1Info  = fieldsInfo.get('spat_hilimit1')
    def lo2Info  = fieldsInfo.get('spat_lolimit2')
    def hi2Info  = fieldsInfo.get('spat_hilimit2')

    if (lo1Info && hi1Info && lo2Info && hi2Info) {  // sanity check all vars
      def ras = ['im_ra1', 'im_ra2', 'im_ra3', 'im_ra4'].collect{fieldsInfo.getValueFor(it)}
      def decs = ['im_dec1', 'im_dec2', 'im_dec3', 'im_dec4'].collect{fieldsInfo.getValueFor(it)}

      lo1Info.setValue(ras.min())
      hi1Info.setValue(ras.max())
      lo2Info.setValue(decs.min())
      hi2Info.setValue(decs.max())
    }
  }


  /**
   * Use the filter value to determine the spatial resolution based on a NIRCam
   * filter-resolution table.
   */
  private void calcSpatialResolution (FieldsInfo fieldsInfo) {
    log.trace("(JwstProcessor.calcSpatialResolution): fieldsInfo=${fieldsInfo}")
    String filter = fieldsInfo.getValueFor('filter')
    if (filter) {
      Double resolution = FILTER_RESOLUTIONS[filter]
      def fieldInfo = fieldsInfo['s_resolution']
      if (resolution && (fieldInfo != null)) {
        fieldInfo['value'] = resolution
      }
    }
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
  private void calcWcsCoords (Map headerFields, FieldsInfo fieldsInfo) {
    log.trace("(JwstProcessor.calcWcsCoords): headerFields=${headerFields}, fieldsInfo=${fieldsInfo}")
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
          "This program currently only handles Tangent Plane projection and cannot yet process files with CTYPE1 of '${ctype1}'.")
      }
    }
  }


  /**
   * Try to compute a value of the correct type for each field in the given
   * field maps which does not already have a value.
   */
  private void computeValuesForFields (Map headerFields, FieldsInfo fieldsInfo) {
    log.trace("(JwstProcessor.computeValuesForFields): headerFields=${headerFields}, fieldsInfo=${fieldsInfo}")

    ////////////////////////////////////////////////////////////////////////////////
    // NOTE: SPECIAL CASE: correct the t_exptime zero value
    //       with a default of 1347.0 per Eiichi Egami 20190626.
    //       Remove this code when the t_exptime field gets real values in the future.
    def tExptime = fieldsInfo.getValueFor('t_exptime')
    if (tExptime == 0.0)
      fieldsInfo.setValueFor('t_exptime', 1347.0 as Double)
    ////////////////////////////////////////////////////////////////////////////////

    fieldsInfo.each { key, fieldInfo ->
      if (!fieldInfo.hasValue()) {          // do not replace existing values
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
  private void computeValueForAField (FieldInfo fieldInfo, Map headerFields, FieldsInfo fieldsInfo) {
    log.trace(
      "(JwstProcessor.computeValueForAField): fI=${fieldInfo}, hF=${headerFields}, fsI=${fieldsInfo}")

    if (fieldInfo.hasValue())               // do not replace existing values
      return                                // exit out now

    def obsCoreKey = fieldInfo['obsCoreKey']
    switch(obsCoreKey) {
      case ['s_ra', 's_dec']:               // coordinate fields extracted from the file
        calcWcsCoords(headerFields, fieldsInfo)
        break
      case [ 'im_naxis1', 'im_naxis2' ]:
        fieldsInfo.copyValue('s_xel1', 'im_naxis1') // s_xel1 already filled by aliasing
        fieldsInfo.copyValue('s_xel2', 'im_naxis2') // s_xel2 already filled by aliasing
        break
      case 's_resolution':
        calcSpatialResolution(fieldsInfo)
        break
      case 'im_pixtype':
        calcPixtype(headerFields, fieldsInfo)
        break
      case 'access_url':                    // use filepath for now (TODO: ENHANCE LATER)
        def filename = fieldsInfo.getValueFor('file_name')
        // NOTE: Temporary kludge for Firefly to return external file mount path:
        if (filename != null)               // TODO LATER: return real URL for image access
          fieldInfo.setValue("file:///external/${filename}" as String)
        break
      case 'instrument_name':               // NIRCam + MODULE value
        def module = fieldsInfo.getValueFor('nircam_module')
        String instName = (module != null) ? "NIRCam-${module}" : "NIRCam"
        fieldInfo.setValue(instName)
        break
      case 'target_name':                   // making something up
        def filename = fieldsInfo.getValueFor('file_name')
        if (filename != null) {
          if (filename.toLowerCase().startsWith("goods_s"))
              fieldInfo.setValue("goods_south")
          else if (filename.toLowerCase().startsWith("goods_n"))
              fieldInfo.setValue("goods_north")
          else
              fieldInfo.setValue("UNKNOWN")
        }
        break
    }
  }


  /**
   * Try to convert the header value string in each field information entry to the
   * correct type (as specified in the field info entry).
   * If successful, the converted value is added back to the corresponding field information.
   */
  private void convertHeaderValues (FieldsInfo fieldsInfo) {
    log.trace("(JwstProcessor.convertHeaderValues): fieldsInfo=${fieldsInfo}")
    fieldsInfo.each { key, fieldInfo ->
      convertAHeaderValue(fieldInfo)
    }
  }

  /**
   * Try to create a value of the correct type for the given field information map.
   * Tries to convert the header value string to the proper datatype. If successful,
   * the value is added back to the field information.
   */
  private void convertAHeaderValue (FieldInfo fieldInfo) {
    log.trace("(JwstProcessor.convertAHeaderValue): fieldInfo=${fieldInfo}")

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
      Utils.logError('JwstProcessor.addValueForAField', msg)
      value = null
    }
    catch (IllegalArgumentException iax) {
      def fitsKey = fieldInfo['hdrKey']     // header key from FITS file
      def msg = "Unknown datatype '${datatype}' for field '${fitsKey}'. Ignoring bad field value."
      Utils.logError('JwstProcessor.addValueForAField', msg)
      value = null
    }

    if (value != null)                      // if we extracted a value
      fieldInfo.setValue(value)             // then save extracted value in the field info map
  }


  private void ensureRequiredFields (FieldsInfo fieldsInfo) {
    log.trace("(JwstProcessor.ensureRequiredFields): fieldsInfo=${fieldsInfo}")
    // TODO: ENHANCE LATER? (TAKE SOME ACTION?)
    fieldsInfo.each { key, fieldInfo ->
      def obsCoreKey = fieldInfo['obsCoreKey']
      if (obsCoreKey && !fieldInfo.hasValue()) { // find ObsCore fields which still have no value
        def reqFld = fieldInfo['required'] ? 'Required' : 'Optional'
        def msg = "${reqFld} field '${obsCoreKey}' still does not have a value."
        if (VERBOSE || DEBUG)
          Utils.logWarning('JwstProcessor.ensureRequiredFields', msg, false)
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
   *  Create and return a WCSLib data structure containing important WCS keywords
   *  and their values, extracted from the given FITS file header fields.
   */
  private WCSKeywords getWcsKeys(headerFields) {
    log.trace("(JwstProcessor.getWcsKeys): headerFields=${headerFields}")

    WCSKeywords keywords = new WCSKeywordsImpl()

    keywords.put('CD1_1', headerFields['CD1_1'] as Double)
    keywords.put('CD1_2', headerFields['CD1_2'] as Double)
    keywords.put('CD2_1', headerFields['CD2_1'] as Double)
    keywords.put('CD2_2', headerFields['CD2_2'] as Double)

    keywords.put('CRPIX1', headerFields['CRPIX1'] as Double)
    keywords.put('CRPIX2', headerFields['CRPIX2'] as Double)

    keywords.put('CRVAL1', headerFields['CRVAL1'] as Double)
    keywords.put('CRVAL2', headerFields['CRVAL2'] as Double)

    keywords.put('CTYPE1', headerFields['CTYPE1'])
    keywords.put('CTYPE2', headerFields['CTYPE2'])

    keywords.put('CUNIT1', headerFields['CUNIT1'])
    keywords.put('CUNIT2', headerFields['CUNIT2'])

    keywords.put('EQUINOX', headerFields['EQUINOX'] as Double)
    keywords.put('NAXIS',   headerFields['NAXIS'] as Integer)
    keywords.put('NAXIS1',  headerFields['NAXIS1'] as Integer)
    keywords.put('NAXIS2',  headerFields['NAXIS2'] as Integer)
    keywords.put('RADESYS', headerFields['RADESYS'])

    return keywords
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

    if (DEBUG)
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

    if (DEBUG)
      log.info("(JwstProcessor.loadFieldsInfo): Read ${recCnt} field information records.")

    return fields
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
      Utils.logError('JwstProcessor.readFitsFile', msg)
      return null                           // signal unable to open/read FITS file
    }

    return fits                             // everything OK: return Fits object
  }


  /**
   * Store the given sky coordinates (RA, DEC) into the corner fields named by the
   * given RA field keyword and DEC field keyword, respectively.
   */
  private void setCornerField (FieldsInfo fieldsInfo, String raFieldKey, String decFieldKey, List sky)  {
    log.trace("(JwstProcessor.setCornerField): raFieldKey=${raFieldKey}, decFieldKey=${decFieldKey}, sky=${sky}")
    if (sky && (sky.size() > 1)) {
      fieldsInfo.setValueFor(raFieldKey, sky[0])
      fieldsInfo.setValueFor(decFieldKey, sky[1])
    }
  }

  /**
   * Convert the given string value to the given datatype. Allowed datatype values
   * are limited: "date", "double", "integer", "string".
   */
  public static def stringToValue (String valueStr, String datatype) {
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

  /**
   * Transform the given pixel coordinates into world coordinates and return a list
   * of the RA and DEC coordinates. Returns null if unable to transform the pixel coordinates.
   */
  private List transformPix2Sky (Transform trans, Double x, Double y) {
    log.trace("(JwstProcessor.transformPix2Sky): trans=${trans}, x=${x}, y=${y}")
    def pix = [x, y] as Double[]
    try {
      Transform.Result sky = trans.pix2sky(pix)
      if (sky && sky.coordinates)
        return [ sky.coordinates[0], sky.coordinates[1] ]
    } catch (Exception ex) {
      return null                           // signal failure
    }
    return null                             // signal failure
  }

}
