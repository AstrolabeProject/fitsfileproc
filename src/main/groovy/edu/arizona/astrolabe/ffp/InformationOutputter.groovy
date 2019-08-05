package edu.arizona.astrolabe.ffp

import java.io.*
import org.apache.logging.log4j.*

/**
 * Class to implement general output methods for the Astrolabe FITS File Processor project.
 *
 *   Written by: Tom Hicks. 8/5/2019.
 *   Last Modified: Initial creation.
 */
class InformationOutputter implements IInformationOutputter {
  static final Logger log = LogManager.getLogger(InformationOutputter.class.getName());

  /** The special keyword for input file information in the field information map. */
  static final String FILE_INFO_KEYWORD = '_FILE_INFO_'

  /** String which defines a comment line in the SQL output. */
  private static final String SQL_COMMENT = '--'


  /** Debug setting: when true, show internal information for debugging. */
  private boolean DEBUG   = false

  /** Verbose setting: when true, show extra information about program operation. */
  private boolean VERBOSE = false

  /** Configuration parameters given to this class in the constructor. */
  private Map config

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
  }


  /** Load the given field information directly into a PostgreSQL database. */
  void intoPostgres (Map fieldsInfo) {
  }

  /** Output the given field information to standard output as a loadable SQL script. */
  void toSQL (Map fieldsInfo) {
    outputFileInfo(fieldsInfo)
  }

  /** Output the given field information to standard output as a JSON string. */
  void toJSON (Map fieldsInfo) {
  }


  /** Output information about the input file as a comment in the output. */
  private void outputFileInfo (Map fieldsInfo) {
    def fileInfo = fieldsInfo[FILE_INFO_KEYWORD]
    if (fileInfo) {
      if (outputFormat == 'sql') {
        print("${SQL_COMMENT} ")
        println(fileInfo?.filePath)
      }
      // TODO: IMPLEMENT JSON LATER
    }
  }

}
