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
 *   Last Modified: Map aliases, get field info. Add column names to resource files.
 */
class JwstProcessor implements IFitsFileProcessor {
  static final Logger log = LogManager.getLogger(JwstProcessor.class.getName());

  /** String which defines a comment line in the JWST resource files. */
  static final String COMMENT_MARKER = "#"

  /** String which defines the line containing column_names in the JWST resource files. */
  static final String COLUMN_NAME_MARKER = "_COLUMN_NAMES_"

  /** String which defines a "not yet implemented" line in the JWST resource files. */
  static final String NOP_ENTRY_KEY = "_NOP_"

  /** Default resource file for header keyword aliases. */
  static final String DEFAULT_ALIASES_FILEPATH = "/jwst-aliases.txt"

  /** Default resource file for header field information. */
  static final String DEFAULT_FIELDS_FILEPATH = "/jwst-fields.txt"


  /** Debug setting: when true, show internal information for debugging. */
  boolean DEBUG   = false

  /** Verbose setting: when true, show extra information about program operation. */
  boolean VERBOSE = false

  /** Configuration parameters given to this class in the constructor. */
  private Map config

  /** List of column names in the header field information file (with default values). */
  private List fieldInfoColumnNames = [ "obsCoreKey", "datatype", "required", "default" ]

  /** Mappping of FITS header keywords to ObsCore keywords.
      Read from a given external file or a default internal resource file. */
  private Map fitsAliases

  /** Map defining header field information for fields used by this processor.
      Read from a given external file or a default internal resource file. */
  private Map fitsFields


  /** Public constructor taking a map of configuration settings. */
  public JwstProcessor (configuration) {
    log.trace("(JwstProcessor.ctor): config=${configuration}")
    this.config = configuration             // save incoming settings in global variable
    this.DEBUG = configuration.DEBUG ?: false
    this.VERBOSE = configuration.VERBOSE ?: false

    // load the FITS field name aliases from a given file path or a default resource path.
    fitsAliases = loadAliases(config.aliasFile)
    if (DEBUG)                              // REMOVE LATER
      fitsAliases.each { entry -> println("${entry.key}=${entry.value}") }

    // load the field info from a given file or a default resource path.
    fitsFields = loadFields(config.fieldsFile)
    if (DEBUG) {                              // REMOVE LATER
      println("COLUMN_NAMES=${fieldInfoColumnNames}")
      fitsFields.each { entry -> println("${entry.key}=${entry.value}") }
    }
  }


  /** Process the single given file. */
  int processAFile (File aFile) {
    log.trace("(FitsFileProcessor.processAFile): aFile=${aFile}")

    Fits fits = readFitsFile(aFile)         // make FITS object from given FITS file
    if (!fits)                              // if unable to open/read FITS file
      return 0                              // then skip this file

    def allKeys = getAllKeys(fits)          // get keywords from FITS file header

    if (DEBUG) {                                         // REMOVE LATER
      println("ALL KEYS(${allKeys.size()}): ${allKeys}") // REMOVE LATER
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

    // process each key from the FITS file header
    allKeys.each { hdrKey ->
      def obsCoreKey = getObsCoreKeyFromAlias(hdrKey)
      def fieldInfo = getObsCoreFieldInfo(obsCoreKey, hdrKey)
      if (fieldInfo) {                      // process if we have info for this ObsCore key
        if (DEBUG) {                        // REMOVE LATER
          println("FIELDINFO=${fieldInfo}") // REMOVE LATER
        }
      }
    }

    return 1
  }



  /**
   * Return a List of all non-comment (key/value pair) keywords
   * in the header of the first HDU of the given FITS file.
   */
  List getAllKeys (Fits refFits) {
    log.trace("(FitsFileProcessor.getAllKeys): refFits=${refFits}")
    Header hdr = refFits.getHDU(0).getHeader()
    return hdr.iterator().findAll{it.isKeyValuePair()}.collect{it.getKey()}
  }

  /**
   * Return a Map of all non-comment (key/value pair) keywords and their values
   * in the header of the first HDU of the given FITS file.
   */
  def getAllFields (Fits refFits) {
    log.trace("(FitsFileProcessor.getAllFields): refFits=${refFits}")
    Header hdr = refFits.getHDU(0).getHeader()
    return hdr.iterator().findAll{it.isKeyValuePair()}.collectEntries {
      [ (it.getKey()) : it.getValue() ] }
  }


  def getFitsFields (Fits refFits) {
    log.trace("(FitsFileProcessor.getFitsFields): refFits=${refFits}")
    Map hdrMap = [:]

    BasicHDU bhdu = refFits.getHDU(0)
    Header hdr = bhdu.getHeader()
    hdrMap = [
      'NAXIS1'   : hdr.getIntValue('NAXIS1'), // FITS required keyword
      'NAXIS2'   : hdr.getIntValue('NAXIS2'), // FITS required keyword
      'AUTHOR'   : bhdu.getAuthor(),
      'obs_creation_date' : bhdu.getCreationDate(),
      'EQUINOX'  : bhdu.getEquinox(),
      'INSTRUME' : bhdu.getInstrument(),
      'OBJECT'   : bhdu.getObject(),
      'DATE-OBS' : bhdu.getObservationDate(),
      'OBSERVER' : bhdu.getObserver(),
      'ORIGIN'   : bhdu.getOrigin(),
      'TELESCOP' : bhdu.getInstrument()
    ]

    return hdrMap.findAll { it.value != null } // filter out null values
  }


  /** Return a field information map for the given ObsCore keyword, or null if none found.
   *  If the optional FITS header keyword is given, it will be added to retrieved
   *  information map (if any).
   */
  Map getObsCoreFieldInfo (obsCoreKey, hdrKey=null) {
  def infoMap = null
    def info = fitsFields.get(obsCoreKey)
    if (info && (info.size() == fieldInfoColumnNames.size())) {
      infoMap = [fieldInfoColumnNames, info].transpose().collectEntries()
      if (hdrKey)
        infoMap << [ "hdrKey": hdrKey ]
    }
    return infoMap
  }

  /** Return the ObsCore keyword for the given FITS header keyword, or null if none found. */
  String getObsCoreKeyFromAlias (hdrKey) {
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


  /** Locate the fields info file, load the fields, and return them. */
  private Map loadFields (File fieldsFile) {
    log.trace("(JwstProcessor.loadFields): fieldsFile=${fieldsFile}")
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
      log.info("(JwstProcessor.loadFields): Reading field information from: ${fieldsFilepath}")

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
        if (flds.size() > 1) {              // must be at least two fields
          fields.put(flds[0], flds)         // store all fields keyed by first field
          recCnt += 1
        }
      }
    }

    if (VERBOSE)
      log.info("(JwstProcessor.loadFields): Read ${recCnt} field information records.")

    return fields
  }


  /**
   * Open, read, and return a Fits object from the given File, which is assumed to be
   * pointing to a valid, readable FITS file.
   * Returns the new Fits object, or null if problems encountered.
   */
  private Fits readFitsFile (File aFile) {
    Fits fits = null
    if (aFile.getName().endsWith('.gz'))
      fits = new Fits(new FileInputStream(aFile))
    else
      fits = new Fits(aFile)

    try {
      fits.read()                             // read the data into FITS object
    }
    catch (Exception iox) {
      def msg = "Invalid FITS Header encountered in file '${aFile.getAbsolutePath()}'. File processing skipped."
      log.warn("(JwstProcessor.readFitsFile): $msg")
      System.err.println("WARNING: $msg")
      return null                           // signal unable to open/read FITS file
    }

    return fits                             // everything OK: return Fits object
  }


    // def fitsFields = getFitsFields(fits)
    // println("FITS FIELDS(${fitsFields.size()}): ${fitsFields}")
    // fitsFields.each { key, val -> println("${key}: ${val}") }

    // def allFields = getAllFields(fits)
    // if (DEBUG) {                            // REMOVE LATER
    //   println("ALL FIELDS(${allFields.size()}): ${allFields}")
    //   allFields.each { key, val -> println("${key}: ${val}") }
    // }

    // NOTE: default for t_exptime given by Eiichi Egami 20190626: 1347
    // NOTE: default for instrument_name = NIRCam + MODULE value
    // NOTE: Ask Eiichi about o_ucd: what is being measured? photo.flux.density? others?

}
