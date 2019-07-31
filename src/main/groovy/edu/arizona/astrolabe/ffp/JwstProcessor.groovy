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
 *   Last Modified: Update for rename of aliases file and addition of field info file.
 */
class JwstProcessor implements IFitsFileProcessor {
  static final Logger log = LogManager.getLogger(JwstProcessor.class.getName());

  static final String COMMENT_MARKER = "#"
  static final String NOP_ENTRY_KEY = "_NOP_"
  static final String DEFAULT_ALIASES_FILEPATH = "/jwst-aliases.txt"
  static final String DEFAULT_FIELDS_FILEPATH = "/jwst-fields.txt"

  Map config                                // configuration information
  Map fitsAliases                           // map renaming FITS field names to ObsCore names
  Map fitsFields                            // map defining fields extracted from a FITS file

  boolean DEBUG   = false                   // when true show internal information for debugging
  boolean VERBOSE = false                   // when true show extra information


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
    if (DEBUG)                              // REMOVE LATER
      fitsAliases.each { entry -> println("${entry.key}=${entry.value}") }
  }


  /** Process the single given file. */
  int processAFile (File aFile) {
    log.trace("(FitsFileProcessor.processAFile): aFile=${aFile}")
    if (DEBUG)                              // REMOVE LATER
      println("FILE: ${aFile.getName()}")

    // make FITS object from given FITS file
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
      log.warn("(JwstProcessor.processAFile): $msg")
      System.err.println("WARNING: $msg")
      return 0
    }

    // def fitsFields = getFitsFields(fits)
    // println("FITS FIELDS(${fitsFields.size()}): ${fitsFields}")
    // fitsFields.each { key, val -> println("${key}: ${val}") }

    def allFields = getAllFields(fits)
    if (DEBUG) {                            // REMOVE LATER
      println("ALL FIELDS(${allFields.size()}): ${allFields}")
      allFields.each { key, val -> println("${key}: ${val}") }
    }

    return 1
  }


  def getAllFields (Fits refFits) {
    log.trace("(FitsFileProcessor.getAllFields): refFits=${refFits}")
    Map hdrMap = [:]

    Header hdr = refFits.getHDU(0).getHeader()
    def iter = hdr.iterator();
    for(HeaderCard card=iter.next(); iter.hasNext(); card=iter.next()) {
      if (card.isKeyValuePair()) {
        def key = card.getKey()
        def val = card.getValue()
        hdrMap << [(key) : val]
      }
    }

    return hdrMap
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
          line.startsWith(NOP_ENTRY_KEY)) {
        // ignore empty lines, comment lines, or not yet implemented lines
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
      else {                                // line passes basic checks
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

}
