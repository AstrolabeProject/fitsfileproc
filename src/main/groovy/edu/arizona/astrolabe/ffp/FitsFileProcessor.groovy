package edu.arizona.astrolabe.ffp

import java.io.*
import java.util.zip.GZIPInputStream

import org.apache.logging.log4j.*

import groovy.cli.commons.CliBuilder
import groovy.transform.InheritConstructors

/**
 * Astrolabe tool to process FITS files, extracting the metadata.
 *   This class parses and validates its arguments, then calls core processing methods.
 *
 *   Written by: Tom Hicks. 7/14/2019.
 *   Last Modified: Add an output directory argument for writing results to.
 */
class FitsFileProcessor {

  static final Logger log = LogManager.getLogger(FitsFileProcessor.class.getName());
  static final List FILE_TYPES = ['.fits', '.fits.gz']
  static final List OUTPUT_FORMATS = ['db', 'json', 'sql']

  static boolean DEBUG   = false
  static boolean VERBOSE = false

  /** Main program entry point. */
  public static void main (String[] args) {

    // read, parse, and validate command line arguments
    def usage = 'java -jar ffp.jar [-h] [-a aliases-file] [-f output-format] [-i info-file] [-t processor-type] [-o output-dir] (FITS-file|FITS-directory)..'
    def cli = new CliBuilder(usage: usage)
    cli.width = 100                         // increase usage message width
    cli.with {
      a(longOpt:  'aliases', args:1, argName: 'filepath',
        'File of aliases (FITS keyword to ObsCore keyword mappings) [default: "jwst-aliases"]')
      d(longOpt: 'debug',
        'Print debugging output in addition to normal processing [default: non debug mode]')
      f(longOpt: 'format',  args:1, argName: 'output-format',
        'Output format for processing results: "db", "json", or "sql" [default: "sql"]')
      h(longOpt: 'help',     'Show usage information.')
      i(longOpt: 'info',    args:1, argName: 'filepath',
        'File listing information on fields to be processed [default: "jwst-fields"].')
      o(longOpt: 'outdir',  args:1, argName: 'outdir',
        'Writeable directory in which to write the generated output file [default: "$PWD/out"].')
      t(longOpt: 'type',    args:1, argName: 'processor-type',
        'Which processor type to use [default: "jwst"]')
      v(longOpt: 'verbose',  'Run in verbose mode [default: non verbose mode].')
    }
    def options = cli.parse(args)           // parse command line

    // validate command line arguments
    if (!options) return                    // exit out on problem
    if (options.h || options.arguments().isEmpty()) {
      cli.usage()                           // show usage and exit on help
      return                                // exit out now
    }

    // set global flags
    this.VERBOSE = options.v ?: false
    this.DEBUG = options.d ?: false

    // check for valid output format specification
    String outputFormat = (options.f ?: 'sql').toLowerCase()
    if (!OUTPUT_FORMATS.contains(outputFormat)) {
      System.err.println("ERROR: Output format argument must be one of: ${OUTPUT_FORMATS.join(', ')}")
      cli.usage()
      System.exit(2)
    }

    // if an external aliases filepath is given, check that it exists and is readable
    File aliasFile = validateAliasesFilepath(options.a ?: null)

    // if an external fields information filepath is given, check that it exists and is readable
    File fieldsFile = validateFieldsFilepath(options.i ?: null)

    // check that the given (or default) output directory exists and is writable
    def outputDir = options.o ?: 'out'
    if (!FileUtils.goodDirPath(outputDir, true)) {    // test for writable directory
      System.err.println(
        "ERROR: Directory '${outputDir}' must exist and be writable. Exiting...")
      System.exit(4)
    }

    // instantiate a specialized processor with the specified settings
    def settings = [ 'DEBUG': DEBUG, 'VERBOSE': VERBOSE,
                     'outputDir': outputDir, 'outputFormat': outputFormat ]
    if (aliasFile)
      settings << [ 'aliasFile': aliasFile ]
    if (fieldsFile)
      settings << [ 'fieldsFile': fieldsFile ]

    // figure out which processor will be used on the input files (currently only one is available):
    IFitsFileProcessor processor = null
    def whichProcessor = options.t ?: 'jwst'
    if (whichProcessor == 'jwst') {
      processor = new JwstProcessor(settings)
    }
    else {
      System.err.println(
        "ERROR: Processor type 'jwst' is currently the only processor type available. Exiting...")
      cli.usage()
      System.exit(3)
    }

    // check the given input paths for validity
    List pathList = validatePathStrings(options.arguments())

    // process the given FITS files and directories
    def procCount = 0
    pathList.each { path ->
      if (! path instanceof java.io.File) {
        System.err.println(
          "ERROR: Found validated path '${path}' which is neither a file nor a directory. Exiting...")
        System.exit(5)
      }
      if (path.isDirectory()) {
        if (VERBOSE)
          log.info("(FitsFileProcessor.main): Processing FITS files in '${path}'")
        procCount += processDirs(processor, path)
      }
      else if (path.isFile()) {
        if (VERBOSE)
          log.info("(FitsFileProcessor.main): Processing FITS file '${path}'")
        procCount += processor.processAFile(path)
      }
      else {  /** should not happen so ignore the invalid path */ }
    }

    if (VERBOSE)
      log.info("(FitsFileProcessor.main): Processed ${procCount} FITS files.")
  }


  /** Tell whether the given filename is to be processed or not. */
  static boolean isAcceptableFilename (String filename) {
    log.trace("(FitsFileProcessor.isAcceptableFilename): filename=$filename")
    // return filename.endsWith('.fits') || filename.endsWith('fits.gz')
    return FILE_TYPES.any { filename.endsWith(it) }
  }

  /** Return a (possibly empty) list of FITS Files in the current directory. */
  static List<File> listFitsFilesInDir (File dir) {
    log.trace("(FitsFileProcessor.listFitsFilesInDir): dir=${dir}")
    return dir.listFiles( new FilenameFilter() {
      boolean accept (java.io.File not_used, java.lang.String filename) {
        return isAcceptableFilename(filename)   // ** directory argument ignored **
      }
    } )
  }

  /** Process the files in all subdirectories of the given top-level directory. */
  static int processDirs (processor, topDirectory) {
    log.trace("(FitsFileProcessor.processDirs): processor=${processor}, topDirectory=${topDirectory}")
    int cnt = processFiles(processor, topDirectory)
    topDirectory.eachDirRecurse { dir ->
      if (FileUtils.goodDirectory(dir)) {
        cnt += processFiles(processor, dir)
      }
    }
    return cnt
  }

  /** Process the files in the given directory. */
  static int processFiles (processor, directory) {
    log.trace("(FitsFileProcessor.processFiles): processor=${processor}, dir=${directory}")
    int cnt = 0
    List fileList = listFitsFilesInDir(directory)
    // def fileList = directory.listFiles(this) as List
    fileList.each { filename ->
      def aFile = FileUtils.goodFile(filename)
      if (!aFile)
        return                      // exit out now if not a valid file
      else {
        cnt += processor.processAFile(aFile) // call processor to process the file
      }
    }
    return cnt
  }


  /**
   * Checks the given aliases filepath for validity, as follows:
   *   if given a null or empty filepath, return null,
   *   if given a non-empty filepath, validate the readability of the file:
   *     if file is readable, return a Java File object for the file,
   *     else exit the program immediately with an error message and exit code.
   * NB: an invalid aliases filepath will cause the program to terminate here!
   */
  static File validateAliasesFilepath (String filepath) {
    log.trace("(FitsFileProcessor.validateAliasesFilepath): filepath=$filepath")
    File aliasFile = null
    if (filepath) {
      aliasFile = FileUtils.goodFilePath(filepath)
      if (!aliasFile) {
        System.err.println(
          "ERROR: Unable to find and read the specified aliases file '${filepath}'. Exiting...")
        System.exit(10)
      }
    }
    return aliasFile
  }

  /**
   * Checks the given fields filepath for validity, as follows:
   *   if given a null or empty filepath, return null,
   *   if given a non-empty filepath, validate the readability of the file:
   *     if file is readable, return a Java File object for the file,
   *     else exit the program immediately with an error message and exit code.
   * NB: an invalid fields filepath will cause the program to terminate here!
   */
  static File validateFieldsFilepath (String filepath) {
    log.trace("(FitsFileProcessor.validateFieldsFilepath): filepath=$filepath")
    File fieldsFile = null
    if (filepath) {
      fieldsFile = FileUtils.goodFilePath(filepath)
      if (!fieldsFile) {
        System.err.println(
          "ERROR: Unable to find and read the specified fields info file '${filepath}'. Exiting...")
        System.exit(11)
      }
    }
    return fieldsFile
  }

  /** Return a (possibly empty) list of valid file/directory paths. */
  static List validatePathStrings (List pathStrings) {
    log.trace("(FitsFileProcessor.validatePathStrings): pathStrings=$pathStrings")
    pathStrings.findResults { pathname ->
      if (isAcceptableFilename(pathname))
        FileUtils.goodFilePath(pathname)
      else
        FileUtils.goodDirPath(pathname)
    }
  }

}


/**
 * Interface specifying behavior for classes which process FITS files for Astrolabe.
 */
interface IFitsFileProcessor {
  /** Process the single given file. */
  int processAFile (File aFile);
}


/**
 * Interface specifying behavior for classes which output information derived from FITS files.
 */
interface IInformationOutputter {
  /** The special keyword for input file information in the field information map. */
  static final String FILE_INFO_KEYWORD = '_FILE_INFO_'

  /** Output the given field information using the current output settings. */
  void outputInformation (Map fieldsInfo);

  /** Load the given field information directly into a PostgreSQL database. */
  void intoPostgres (Map fieldsInfo);
}


/**
 * Exception which is thrown by a Processor when it fails to entirely process a given file.
 */
@InheritConstructors
class AbortFileProcessingException extends RuntimeException { }
