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
 *   Last Modified: Add AbortFileProcessingException.
 */
class FitsFileProcessor {

  static final Logger log = LogManager.getLogger(FitsFileProcessor.class.getName());
  static final List FILE_TYPES = ['.fits', '.fits.gz']

  static boolean DEBUG   = false
  static boolean VERBOSE = false

  /** Main program entry point. */
  public static void main (String[] args) {

    // read, parse, and validate command line arguments
    def usage = 'java -jar ffp.jar [-h] [-a aliases] [-f fields] [-t processor-type] (FITS-file|FITS-directory)..'
    def cli = new CliBuilder(usage: usage)
    cli.width = 100                         // increase usage message width
    cli.with {
      a(longOpt:  'aliases',   args:1, argName: 'aliases',
        'File containing custom FITS header field name aliases (default "jwst-aliases")')
      h(longOpt:  'help',     'Show usage information.')
      d(longOpt:  'debug',
        'Print debugging output in addition to normal processing (default false)')
      f(longOpt:  'fields',   args:1, argName: 'fields',
        'File containing custom FITS header field information (default "jwst-fields").')
      t(longOpt:  'type',      args:1, argName: 'procType',
        'Which processor type to use (default: "jwst")')
      v(longOpt:  'verbose',  'Run in verbose mode (default: non verbose mode).')
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

    // if an external aliases filepath is given, check that it exists and is readable
    File aliasFile = validateAliasesFilepath(options.a ?: null)

    // if an external fields info filepath is given, check that it exists and is readable
    File fieldsFile = validateFieldsFilepath(options.f ?: null)

    // instantiate a specialized processor with the specified settings
    def settings = [ 'DEBUG': DEBUG, 'VERBOSE': VERBOSE ]
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
        "Error: Processor type 'jwst' is currently the only type available. Exiting...")
      System.exit(2)
    }

    // check the given input paths for validity
    List pathList = validatePathStrings(options.arguments())

    // process the given FITS files and directories
    def procCount = 0
    pathList.each { path ->
      if (! path instanceof java.io.File) {
        System.err.println(
          "Error: Found validated path '${path}' which is neither a file nor a directory. Exiting...")
        System.exit(3)
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

    // processor.exit()                           // do any processor instance cleanup

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
          "Error: Unable to find and read the specified aliases file '${filepath}'. Exiting...")
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
          "Error: Unable to find and read the specified fields info file '${filepath}'. Exiting...")
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

  /** Do any needed processor instance cleanup. */
  // void exit()
}


/**
 * Exception which is thrown by a Processor when it fails to entirely process a given file.
 */
@InheritConstructors
class AbortFileProcessingException extends RuntimeException { }
