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
 *   Last Modified: Begin extraction: get basic FITS values via nom-tam-fits BasicHDU class.
 */
class JwstProcessor implements IFitsFileProcessor {
  static final Logger log = LogManager.getLogger(JwstProcessor.class.getName());

  static final String COMMENT_MARKER = "#"
  static final String NOP_ENTRY_KEY = "_NOP_"
  static final String DEFAULT_MAP_FILEPATH = "/jwst-mappings.txt"

  Map config                                // configuration information
  Map MAPPINGS
  boolean DEBUG   = false
  boolean VERBOSE = false

  /** Public constructor taking a map of configuration settings. */
  public JwstProcessor (configuration) {
    log.trace("(JwstProcessor.ctor): config=${configuration}")
    this.config = configuration             // save incoming settings in global variable
    this.DEBUG = configuration.DEBUG ?: false
    this.VERBOSE = configuration.VERBOSE ?: false

    // validate the specified mappings file, if given
    File mapfile = null
    if (config.mapfilename) {
      mapfile = Utils.goodFilePath(config.mapfilename)
      if (!mapfile) {
        System.err.println(
          "Unable to find and read specified mappings file '${config.mapfilename}'. Exiting...")
        System.exit(10)
      }
    }

    // load the FITS fieldname mappings data
    MAPPINGS = loadMappings(mapfile)
    if (DEBUG)                              // REMOVE LATER
      MAPPINGS.each { entry -> println("${entry.key}=${entry.value}") }
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
    fits.read()                             // read the data into FITS object

    def fitsFields = getFitsFields(fits)
    println("FITS FIELDS(${fitsFields.size()}): ${fitsFields}")
    fitsFields.each { key, val -> println("${key}: ${val}") }

    def allFields = getAllFields(fits)
    println("ALL FIELDS(${allFields.size()}): ${allFields}")
    allFields.each { key, val -> println("${key}: ${val}") }

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


  /** Locate the mappings file, load the mappings, and return them. */
  private Map loadMappings (File mapfile) {
    log.trace("(JwstProcessor.loadMappings): mapfile=${mapfile}")
    def mCnt = 0
    def mappings = [:]
    def mapStream
    def mapName = DEFAULT_MAP_FILEPATH

    if (mapfile) {                          // if given external map file, use it
      mapStream = new FileInputStream(mapfile)
      mapName = mapfile.getAbsolutePath()
    }
    else                                    // else fallback to default resource
      mapStream = this.getClass().getResourceAsStream(DEFAULT_MAP_FILEPATH);

    if (VERBOSE)
      log.info("(JwstProcessor.loadMappings): Reading mappings from: ${mapName}")

    def inSR = new InputStreamReader(mapStream, 'UTF8')
    inSR.eachLine { line ->
      if (line.trim().isEmpty() ||
          line.startsWith(COMMENT_MARKER) ||
          line.startsWith(NOP_ENTRY_KEY)) {
        // ignore empty lines, comment lines, or not yet implemented lines
      }
      else {                                // line passes basic checks
        def fields = line.split(',')
        if (fields.size() == 2) {
          mappings.put(fields[0].trim(), fields[1].trim())
          mCnt += 1
        }
      }
    }

    if (VERBOSE)
      log.info("(JwstProcessor.loadMappings): Read ${mCnt} field mappings.")

    return mappings
  }

}
