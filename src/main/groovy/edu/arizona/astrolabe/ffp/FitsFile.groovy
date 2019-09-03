package edu.arizona.astrolabe.ffp

import java.io.*
import org.apache.logging.log4j.*

import nom.tam.fits.*

/**
 * Astrolabe JWST-specific FITS file wrapper class.
 *   This class implements a facade for a single FITS file.
 *
 *   Written by: Tom Hicks. 9/3/2019.
 *   Last Modified: Initial split from fits utils class.
 */
class FitsFile {
  static final Logger log = LogManager.getLogger(FitsFile.class.getName());

  /** Debug setting: when true, show internal information for debugging. */
  private boolean DEBUG   = false

  /** Verbose setting: when true, show extra information about program operation. */
  private boolean VERBOSE = false

  /** Configuration parameters given to this class in the constructor. */
  private Map config

  /** The original java.io.File object from which this instance was created. */
  private File theFile

  /** The encapsulated FITS file object. */
  private Fits theFitsFile


  /**
   * Public constructor taking a map of configuration settings. May throw
   * an Exception if unable to read the specified file as a FITS file.
   */
  public FitsFile (File aFile, configuration) {
    log.trace("(FitsFile.ctor): aFile=${aFile}, config=${configuration}")
    config = configuration                  // save incoming settings in global variable
    DEBUG = configuration.DEBUG ?: false
    VERBOSE = configuration.VERBOSE ?: false

    theFile = aFile
    theFitsFile = readFitsFile(aFile)       // actually read the FITS file
  }


  /**
   * Return a List of all non-comment (key/value pair) keywords in the header of the
   * specified HDU of the current FITS file. By default, the first HDU is used.
   */
  public List getHeaderKeys (whichHDU=0) {
    log.trace("(FitsFile.getHeaderKeys): whichHDU=${whichHDU}")
    Header header = theFitsFile.getHDU(whichHDU).getHeader() // get header from specified HDU
    return header.iterator().findAll{it.isKeyValuePair()}.collect{it.getKey()}
  }

  /**
   * Return a Map of all non-comment (key/value pair) keywords and their values in the
   * header of the specified HDU of the current FITS file. By default, the first HDU is used.
   */
  public Map getHeaderFields (whichHDU=0) {
    log.trace("(FitsFile.getHeaderFields): whichHDU=${whichHDU}")
    Header header = theFitsFile.getHDU(whichHDU).getHeader() // get header from specified HDU
    return header.iterator().findAll{it.isKeyValuePair()}.collectEntries {
      [ (it.getKey()) : it.getValue() ]
    }
  }

  /** Tell whether the current FITS file is a FITS catalog or not. */
  public boolean isCatalogFile () {
    log.trace("(FitsFile.isCatalogFile)")
    // TODO: IMPLEMENT THIS METHOD FOR REAL
    return theFile.getName().startsWith('JADE') // TODO: FIX THIS LATER, just for testing now
  }

  /**
   * Open, read, and return a Fits object from the given File, which is assumed to be
   * pointing to a valid, readable FITS file.
   * Returns the newly filled Fits object, or throws an Exception if problems encountered.
   */
  protected Fits readFitsFile (File aFile) {
    log.trace("(FitsFile.readFitsFile): aFile=${aFile}")
    Fits fitsObj = null
    if (aFile.getName().endsWith('.gz'))
      fitsObj = new Fits(new FileInputStream(aFile))
    else
      fitsObj = new Fits(aFile)
    fitsObj.read()                          // try to read the data into FITS object
    return fitsObj                          // return the now filled FITS object
  }

}
