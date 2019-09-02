package edu.arizona.astrolabe.ffp

import java.io.*
import org.apache.logging.log4j.*

import nom.tam.fits.*
import ca.nrc.cadc.wcs.*
import ca.nrc.cadc.wcs.Transform.Result

/**
 * Astrolabe JWST-specific FITS file processor project.
 *   This class implements shared utility methods for FITS file processing (all public static).
 *
 *   Written by: Tom Hicks. 8/28/2019.
 *   Last Modified: Allow to get headers from other HDUs. Add isCatalogFile testing stub.
 */
class FitsUtils {
  static final Logger log = LogManager.getLogger(FitsUtils.class.getName());

  /**
   * Calculate the corner points for the current image, given the FITS file header fields.
   * The calculated corners are returned as a list of pairs of RA/DEC coordinates.
   */
  public static List calcCorners (Map headerFields) {
    log.trace("(FitsUtils.calcCorners): headerFields=${headerFields}")

    WCSKeywords wcs = getWcsKeys(headerFields)   // find WCS keywords in the FITS file headers
    Double naxis1 = wcs.getDoubleValue('NAXIS1') // max RA pixel index
    Double naxis2 = wcs.getDoubleValue('NAXIS2') // max DEC pixel index
    Transform trans = new Transform(wcs)         // create a WCS Transform from the WCS keywords

    // build list of pixel corner coordinates
    List pixelCorners = [ [1.0, 1.0], [1.0, naxis2], [naxis1, naxis2], [naxis1, 1.0] ]

    return pixelCorners.collect { pair ->       // for each pair of pixel coordinates
      transformPix2Sky(trans, pair[0], pair[1]) // return the corresponding sky coordinates
    }.findAll()                                 // remove any failed conversions
  }


  /**
   * Try to convert the header value string in each field information entry to the
   * correct type (as specified in the field info entry).
   * If successful, the converted value is added back to the corresponding field information.
   */
  public static void convertHeaderValues (FieldsInfo fieldsInfo) {
    log.trace("(FitsUtils.convertHeaderValues): fieldsInfo=${fieldsInfo}")
    fieldsInfo.each { key, fieldInfo ->
      convertAHeaderValue(fieldInfo)
    }
  }

  /**
   * Try to create a value of the correct type for the given field information map.
   * Tries to convert the header value string to the proper datatype. If successful,
   * the value is added back to the field information.
   */
  protected static void convertAHeaderValue (FieldInfo fieldInfo) {
    log.trace("(FitsUtils.convertAHeaderValue): fieldInfo=${fieldInfo}")

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
      Utils.logError('FitsUtils.convertAHeaderValue', msg)
      value = null
    }
    catch (IllegalArgumentException iax) {
      def fitsKey = fieldInfo['hdrKey']     // header key from FITS file
      def msg = "Unknown datatype '${datatype}' for field '${fitsKey}'. Ignoring bad field value."
      Utils.logError('FitsUtils.convertAHeaderValue', msg)
      value = null
    }

    if (value != null)                      // if we extracted a value
      fieldInfo.setValue(value)             // then save extracted value in the field info map
  }


  /**
   * Return a List of all non-comment (key/value pair) keywords in the header of the
   * specified HDU of the given FITS file. By default, the first HDU is used.
   */
  public static List getHeaderKeys (Fits fits, whichHDU=0) {
    log.trace("(FitsUtils.getHeaderKeys): fits=${fits}")
    Header header = fits.getHDU(whichHDU).getHeader() // get the header from the specified HDU
    return header.iterator().findAll{it.isKeyValuePair()}.collect{it.getKey()}
  }

  /**
   * Return a Map of all non-comment (key/value pair) keywords and their values in the
   * header of the specified HDU of the given FITS file. By default, the first HDU is used.
   */
  public static Map getHeaderFields (File aFile, whichHDU=0) {
    log.trace("(FitsUtils.getHeaderFields): aFile=${aFile}")

    Fits fits = FitsUtils.readFitsFile(aFile) // make FITS object from given FITS file
    if (!fits)                                // if unable to open/read FITS file
      return null                             // then signal failure

    Header header = fits.getHDU(whichHDU).getHeader() // get the header from the specified HDU
    return header.iterator().findAll{it.isKeyValuePair()}.collectEntries {
      [ (it.getKey()) : it.getValue() ]
    }
  }


  /** Tell whether the given File is a FITS catalog or not. */
  public static boolean isCatalogFile (File aFile) {
    log.trace("(FitsUtils.isCatalogFile): aFile=${aFile}")
    // TODO: IMPLEMENT THIS METHOD FOR REAL
    return aFile.getName().startsWith('JADE') // TODO: FIX THIS LATER, just for testing now
  }


  /**
   * Open, read, and return a Fits object from the given File, which is assumed to be
   * pointing to a valid, readable FITS file.
   * Returns the new Fits object, or null if problems encountered.
   */
  public static Fits readFitsFile (File aFile) {
    log.trace("(FitsUtils.readFitsFile): aFile=${aFile}")
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
      Utils.logError('FitsUtils.readFitsFile', msg)
      return null                           // signal unable to open/read FITS file
    }

    return fits                             // everything OK: return Fits object
  }


  /**
   * Convert the given string value to the given datatype. Allowed datatype values
   * are limited: "date", "double", "integer", "string".
   */
  public static def stringToValue (String valueStr, String datatype) {
    log.trace("(FitsUtils.stringToValue): valueStr='${valueStr}', datatype='${datatype}'")

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
   * Transform each pair of pixel coordinates in the given list to a pair of
   * WCS coordinates, using WCS keywords found in the given FITS file headers.
   */
  // public static List transformPixelList2Sky (headerFields, List pixelPairs) {
  //   WCSKeywords wcs = getWcsKeys(headerFields) // find WCS keywords in the FITS file headers
  //   Transform trans = new Transform(wcs)       // create a WCS Transform from the WCS keywords
  //   return pixelPairs.collect { pair ->        // for each pair of pixel coordinates
  //     transformPix2Sky(trans, pair[0], pair[1]) // return the corresponding sky coordinates
  //   }.findAll()                                // remove any failed conversions
  // }

  /**
   * Transform the given pixel coordinates into world coordinates and return a list
   * of the RA and DEC coordinates. Returns null if unable to transform the pixel coordinates.
   */
  protected static List transformPix2Sky (Transform trans, Double x, Double y) {
    log.trace("(FitsUtils.transformPix2Sky): trans=${trans}, x=${x}, y=${y}")
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

  /**
   *  Create and return a WCSLib data structure containing important WCS keywords
   *  and their values, extracted from the given FITS file header fields.
   */
  protected static WCSKeywords getWcsKeys(headerFields) {
    log.trace("(FitsUtils.getWcsKeys): headerFields=${headerFields}")

    WCSKeywords keywords = new WCSKeywordsImpl()

    keywords.put('CD1_1',   headerFields['CD1_1'] as Double)
    keywords.put('CD1_2',   headerFields['CD1_2'] as Double)
    keywords.put('CD2_1',   headerFields['CD2_1'] as Double)
    keywords.put('CD2_2',   headerFields['CD2_2'] as Double)

    keywords.put('CRPIX1',  headerFields['CRPIX1'] as Double)
    keywords.put('CRPIX2',  headerFields['CRPIX2'] as Double)

    keywords.put('CRVAL1',  headerFields['CRVAL1'] as Double)
    keywords.put('CRVAL2',  headerFields['CRVAL2'] as Double)

    keywords.put('CDELTA1', headerFields['CRVAL1'] as Double)
    keywords.put('CDELTA2', headerFields['CRVAL2'] as Double)

    keywords.put('CTYPE1',  headerFields['CTYPE1'])
    keywords.put('CTYPE2',  headerFields['CTYPE2'])

    keywords.put('CUNIT1',  headerFields['CUNIT1'])
    keywords.put('CUNIT2',  headerFields['CUNIT2'])

    keywords.put('EQUINOX', headerFields['EQUINOX'] as Double)
    keywords.put('NAXIS',   headerFields['NAXIS'] as Integer)
    keywords.put('NAXIS1',  headerFields['NAXIS1'] as Integer)
    keywords.put('NAXIS2',  headerFields['NAXIS2'] as Integer)
    keywords.put('RADESYS', headerFields['RADESYS'])

    return keywords
  }

}
