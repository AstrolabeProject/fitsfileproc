package edu.arizona.astrolabe.ffp

import java.io.*
import java.text.SimpleDateFormat
import org.apache.logging.log4j.*

/**
 * Class to implement general output methods for the Astrolabe FITS File Processor project.
 *
 *   Written by: Tom Hicks. 8/5/2019.
 *   Last Modified: Refactor for new field information structures.
 */
class InformationOutputter implements IInformationOutputter {
  static final Logger log = LogManager.getLogger(InformationOutputter.class.getName());

  /** String which defines a comment line in the SQL output. */
  private static final String SQL_COMMENT = '--'

  // TODO: LATER read DB parameters from somewhere:
  private final String tableName = 'sia.jwst'

  /** Debug setting: when true, show internal information for debugging. */
  private boolean DEBUG   = false

  /** Verbose setting: when true, show extra information about program operation. */
  private boolean VERBOSE = false

  /** Configuration parameters given to this class in the constructor. */
  private Map config

  /** An output file created within the output directory. */
  private File outputFile = null

  /** A string specifying the output. */
  private String outputFormat = 'sql'

  /** List of field names to skip when outputting fields. */
  private List skipFieldList = [ ]


  /** Public constructor taking a map of configuration settings. */
  public InformationOutputter (configuration) {
    log.trace("(InformationOutputter.ctor): config=${configuration}")
    config = configuration                  // save incoming settings in global variable
    DEBUG = configuration.DEBUG ?: false
    VERBOSE = configuration.VERBOSE ?: false
    outputFormat = configuration?.outputFormat
    def outputDir = configuration.outputDir ?: 'out'
    outputFile = new File(genOutputFilePath(outputDir))
  }

  /** Output the given field information using the current output settings. */
  void outputInformation (FieldsInfo fieldsInfo) {
    if (outputFormat in ['sql', 'json']) {
      outputFile.append(makeFileInfo(fieldsInfo))
      outputFile.append('\n')
      outputFile.append(makeDataLine(fieldsInfo))
      outputFile.append('\n')
    }
  }

  /** Load the given field information directly into a PostgreSQL database. */
  void intoPostgres (FieldsInfo fieldsInfo) {
    // TODO: IMPLEMENT LATER
  }


  /**
   * Return a unique output filepath, within the specified directory, for the result file.
   */
  private String genOutputFilePath (String outputDir) {
    def sdf = new SimpleDateFormat("yyyyMMdd_HHmmss-SSS")
    def now = sdf.format(new Date())
    return "${outputDir}/ffp-${now}.${outputFormat}"
  }


  /** Return a string which formats the given field information. */
  private String makeDataLine (FieldsInfo fieldsInfo) {
    log.trace("(InformationOutputter.makeDataLine): fieldsInfo=${fieldsInfo}")
    if (outputFormat == 'sql') {
      return toSQL(fieldsInfo)
    }
    // TODO: handle JSON
  }


  /** Return a string containing information about the input file formatted as a comment. */
  private String makeFileInfo (FieldsInfo fieldsInfo) {
    log.trace("(InformationOutputter.makeFileInfo): fieldsInfo=${fieldsInfo}")

    StringBuffer buf = new StringBuffer()
    def fnameInfo = fieldsInfo.get('file_name')
    def fpathInfo = fieldsInfo.get('file_path')
    def fsizeInfo = fieldsInfo.get('access_estsize') // estimated size is the size of the file

    if (outputFormat == 'sql') {
      buf.append("${SQL_COMMENT} ")
      if (fnameInfo != null) {
        buf.append(fnameInfo?.getValue())
        buf.append(' ')
      }
      if (fsizeInfo != null) {
        buf.append(fsizeInfo?.getValue())
        buf.append(' ')
      }
      if (fpathInfo != null) {
        buf.append(fpathInfo?.getValue())
      }
    }
    // TODO: handle JSON

    return buf.toString()
  }


  /** Return the given file information formatted as an SQL string. */
  private String toSQL (FieldsInfo fieldsInfo) {
    log.trace("(InformationOutputter.toSQL): fieldsInfo=${fieldsInfo}")
    def valued = fieldsInfo.findAll { key, fieldInfo -> fieldInfo.hasValue() }
    def keys = valued.keySet().join(', ')
    def vals = valued.values().collect{it['value']}
    def values = vals.collect {(it instanceof String) ? "'${it}'" : it}.join(', ')
    return "insert into ${tableName} (${keys}) values (${values});"
  }

  /** Return the given file information formatted as a JSON string. */
  private String toJSON (FieldsInfo fieldsInfo) {
    log.trace("(InformationOutputter.toJSON): fieldsInfo=${fieldsInfo}")
  }

}


/**
 * Interface specifying behavior for classes which output information derived from FITS files.
 */
interface IInformationOutputter {
  /** The special keyword for input file information in the field information map. */
  static final String FILE_INFO_KEYWORD = '_FILE_INFO_'

  /** Output the given field information using the current output settings. */
  void outputInformation (FieldsInfo fieldsInfo);

  /** Load the given field information directly into a PostgreSQL database. */
  void intoPostgres (FieldsInfo fieldsInfo);
}
