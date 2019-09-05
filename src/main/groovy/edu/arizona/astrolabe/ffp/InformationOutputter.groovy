package edu.arizona.astrolabe.ffp

import java.io.*
import java.text.SimpleDateFormat
import org.apache.logging.log4j.*

/**
 * Class to implement general output methods for the Astrolabe FITS File Processor project.
 *
 *   Written by: Tom Hicks. 8/5/2019.
 *   Last Modified: Enhance output interface for catalogs: add header and footer output.
 */
class InformationOutputter implements IInformationOutputter {
  static final Logger log = LogManager.getLogger(InformationOutputter.class.getName());

  /** String which defines a comment line in the SQL output. */
  private static final String SQL_COMMENT = '--'

  // TODO: LATER read DB parameters from somewhere:
  private final String imageTableName = 'sia.jwst'
  private final String catalogTableName = 'sia.jcat'
  private final String isPublicValue = '0'  // 0 means is_public = false

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


  /** Load the given field information directly into a PostgreSQL database. */
  public void intoPostgres (FieldsInfo fieldsInfo) {
    // TODO: IMPLEMENT LATER
  }


  /** Output the given field information using the current output settings. */
  public void outputImageInfo (FieldsInfo fieldsInfo) {
    if (outputFormat == 'sql') {
      outputFile.append(makeFileInfo(fieldsInfo))
      outputFile.append('\n')
      outputFile.append(makeDataLine(fieldsInfo))
      outputFile.append('\n')
    }
    // TODO: handle JSON
  }


  /** Begin the output of the catalog information using the current output settings. */
  public void outputCatalogHeader (File aFile) {
    if (outputFormat == 'sql') {
      outputFile.append('begin;\n')
      outputFile.append(makeFileInfo(aFile))
      outputFile.append('\n')
    }
    // TODO: handle JSON
  }

  /** Output the given catalog row using the current output settings. */
  public void outputCatalogRow (Object[] row) {
    log.trace("(InformationOutputter.outputCatalogRow): row=${row}")
    if (outputFormat == 'sql') {
      outputFile.append(toSQL(row))
      outputFile.append('\n')
    }
    // TODO: handle JSON
  }

  /** End the output of the catalog information using the current output settings. */
  public void outputCatalogFooter () {
    if (outputFormat == 'sql') {
      outputFile.append('commit;\n')
    }
    // TODO: handle JSON
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
    else if (outputFormat == 'json') {
      return toJSON(fieldsInfo)
    }
  }


  /** Return a string containing information about the input file formatted as a comment. */
  private String makeFileInfo (FieldsInfo fieldsInfo) {
    log.trace("(InformationOutputter.makeFileInfo): fieldsInfo=${fieldsInfo}")
    def fnameInfo = fieldsInfo.get('file_name')
    def fpathInfo = fieldsInfo.get('file_path')
    def fsizeInfo = fieldsInfo.get('access_estsize') // estimated size is the size of the file
    return fileInfoToString(fnameInfo.getValue(), fsizeInfo.getValue(), fpathInfo.getValue())
  }

  /** Return a string containing information about the given file formatted as a comment. */
  private String makeFileInfo (aFile) {
    log.trace("(InformationOutputter.makeFileInfo): aFile=${aFile}")
    def fileName = aFile.getName()
    def filePath = aFile.getAbsolutePath()
    def fileSize = aFile.length()
    return fileInfoToString(fileName, filePath, fileSize)
  }

  /** Format the given file information strings into an appropriate output string. */
  private String fileInfoToString (fileName, filePath, fileSize) {
    log.trace("(InformationOutputter.fileInfoToString): name=${fileName}, path=${filePath}, size=${fileSize}")
    StringBuffer buf = new StringBuffer()

    if (outputFormat == 'sql') {
      buf.append("${SQL_COMMENT} ")
      if (fileName != null) {
        buf.append(fileName)
        buf.append(' ')
      }
      if (fileSize != null) {
        buf.append(fileSize)
        buf.append(' ')
      }
      if (filePath != null) {
        buf.append(filePath)
      }
    }

    // TODO: handle JSON
    return buf.toString()
  }


  /** Return the given field information formatted as an SQL string. */
  private String toSQL (FieldsInfo fieldsInfo) {
    log.trace("(InformationOutputter.toSQL): fieldsInfo=${fieldsInfo}")
    def valued = fieldsInfo.findAll { key, fieldInfo -> fieldInfo.hasValue() }
    def keys = valued.keySet().join(', ')
    def vals = valued.values().collect{it['value']}
    def values = vals.collect {(it instanceof String) ? "'${it}'" : it}.join(', ')
    return "insert into ${imageTableName} (${keys}) values (${values});"
  }

  /** Return the given field information formatted as a JSON string. */
  private String toJSON (FieldsInfo fieldsInfo) {
    log.trace("(InformationOutputter.toJSON): fieldsInfo=${fieldsInfo}")
    return "[]"                             // JSON NOT YET IMPLEMENTED
  }

  /** Return the given catalog row formatted as an SQL string. */
  private String toSQL (Object[] row) {
    log.trace("(InformationOutputter.toSQL): row=${row}")
    StringBuffer buf = new StringBuffer()
    buf.append("insert into ${catalogTableName} values (")
    row.each { col ->
      buf.append(col)
      buf.append(',')
    }
    buf.append("${isPublicValue});")        // add the is_public flag last
    return buf.toString()
  }

  /** Return the given catalog row formatted as a JSON string. */
  private String toJSON (Object[] row) {
    log.trace("(InformationOutputter.toJSON): row=${row}")
    return "[]"                             // JSON NOT YET IMPLEMENTED
  }
}


/**
 * Interface specifying behavior for classes which output information derived from FITS files.
 */
interface IInformationOutputter {
  /** The special keyword for input file information in the field information map. */
  static final String FILE_INFO_KEYWORD = '_FILE_INFO_'

  /** Load the given field information directly into a PostgreSQL database. */
  public void intoPostgres (FieldsInfo fieldsInfo);

  /** Output the given field information using the current output settings. */
  public void outputImageInfo (FieldsInfo fieldsInfo);

  /** End the output of the catalog information using the current output settings. */
  public void outputCatalogFooter ();

  /** Begin the output of the catalog information using the current output settings. */
  public void outputCatalogHeader (File aFile);

  /** Output the given catalog row using the current output settings. */
  public void outputCatalogRow (Object[] row);
}
