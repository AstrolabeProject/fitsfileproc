package edu.arizona.astrolabe.extractor

import java.io.*
import java.util.zip.GZIPInputStream

import org.apache.logging.log4j.*

import groovy.cli.commons.CliBuilder

/**
 * Astrolabe FITS file metadata extractor tool.
 *   This class parses and validates arguments, then calls core processing methods.
 *
 *   Written by: Tom Hicks. 7/14/2019.
 *   Last Modified: Handle external mappings file and default mappings resource.
 */
class Extractor implements FilenameFilter {

  static final Logger log = LogManager.getLogger(Extractor.class.getName());

  static final String DEFAULT_MAP_FILEPATH = "/jwst-mappings.txt"

  public boolean DEBUG   = false
  public boolean VERBOSE = false

  /** Main program entry point. */
  public static void main (String[] args) {
    // read, parse, and validate command line arguments
    def usage = 'extractor [-h] [-m mapfilepath] (FITS-file|FITS-directory)..'
    def cli = new CliBuilder(usage: usage)
    cli.width = 100                         // increase usage message width
    cli.with {
      h(longOpt:  'help',     'Show usage information.')
      d(longOpt:  'debug',
        'Print debugging output in addition to normal processing (default false).')
      m(longOpt:  'mapfile',   args:1, argName: 'mapfile',
        'File containing FITS fieldname mappings.')
      v(longOpt:  'verbose',  'Run in verbose mode (default: non-verbose).')
    }
    def options = cli.parse(args)           // parse command line

    // validate command line arguments
    if (!options) return                    // exit out on problem
    if (options.h || options.arguments().isEmpty()) {
      cli.usage()                           // show usage and exit on help
      return                                // exit out now
    }

    // instantiate this class with raw options
    def xtor = new Extractor(options)

    // validate the specified mappings file, if given
    File mapfile = null
    if (options.m) {
      mapfile = xtor.goodFilePath(options.m)
      if (!mapfile) {
        System.err.println(
          "Unable to find and read specified mappings file '${options.m}'. Exiting...")
        System.exit(3)
      }
    }

    // load the FITS fieldname mappings data
    def mappings = xtor.loadMappings(mapfile, options)

    // create worker with the specified settings
    def settings = [ 'DEBUG':    xtor.DEBUG,
                     'MAPPINGS': mappings,
                     'VERBOSE':  xtor.VERBOSE ]
    def worker = new Worker(settings)

    // check the given input paths for validity
    List pathList = xtor.validatePathStrings(options.arguments())

    // process the given FITS files and directories
    def procCount = 0
    pathList.each { path ->
      if (! path instanceof java.io.File) {
        System.err.println(
          "Found validated path '${path}' which is neither a file nor a directory. Exiting...")
        System.exit(4)
      }
      if (path.isDirectory()) {
        if (xtor.VERBOSE)
          log.info("(Extractor.main): Processing FITS files in '${path}'")
        procCount += xtor.processDirs(worker, path)
      }
      else if (path.isFile()) {
        if (xtor.VERBOSE)
          log.info("(Extractor.main): Processing FITS file '${path}'")
        procCount += xtor.processAFile(worker, path)
      }
      else {  /** should not happen so ignore the invalid path */ }
    }

    // worker.exit()                           // cleanup worker
    if (xtor.VERBOSE)
      log.info("(Extractor.main): Processed ${procCount} FITS files.")
  }


  /** Public constructor taking a map of ingest options. */
  public Extractor (options) {
    log.trace("(Extractor.init): options=${options}")
    VERBOSE = options.v ?: false
    DEBUG = options.d ?: false
  }


  /** This class implements java.io.FilenameFilter with this method. */
  boolean accept (java.io.File dir, java.lang.String filename) {
    log.trace("(Extractor.accept): dir=${dir}, filename=${filename}")
    return isAcceptableFilename(filename)   // ** dir argument ignored **
  }

  /** Tell whether the given filename is to be processed or not. */
  boolean isAcceptableFilename (String filename) {
    log.trace("(Extractor.isAcceptableFilename): filename=$filename")
    // return FILE_TYPES.any { filename.endsWith(it) } // for a set of file types
    return filename.endsWith('.fits')
  }

  /** Return the basename of the given filename string. */
  def fileBasename (filename) {
    log.trace("(Extractor.fileBasename): filename=$filename")
    return filename.substring(0,filename.indexOf('.'))
  }

  /** Return true if the given file is a directory, readable and, optionally, writeable. */
  def goodDirectory (File dir, writeable=false) {
    log.trace("(Extractor.goodDirectory): dir=$dir")
    return (dir && dir.isDirectory() && dir.canRead() && (!writeable || dir.canWrite()))
  }

  /** If first argument is a path string to a readable directory return it else return null. */
  File goodDirPath (dirPath, writeable=false) {
    log.trace("(Extractor.goodDirPath): dirPath=$dirPath")
    if (dirPath.isEmpty())                  // sanity check
      return null
    def dir = new File(dirPath)
    return (goodDirectory(dir) ? dir : null)
  }

  /** If given filename string references a readable file return the file else return null. */
  File goodFile (File directory, String filename) {
    log.trace("(Extractor.goodFile): directory=${directory}, filename=${filename}")
    def fyl = new File(directory, filename)
    return (readableFile(fyl)) ? fyl : null
  }

  /** If given file path string references a readable file return the file else return null. */
  File goodFilePath (filePath) {
    log.trace("(Extractor.goodFilePath): filePath=$filePath")
    if (filePath.isEmpty())                  // sanity check
      return null
    def fyl = new File(filePath)
    return (readableFile(fyl) ? fyl : null)
  }

  /** Return true if the given file is a file and readable. */
  def readableFile (File fyl) {
    log.trace("(Extractor.readableFile): fyl=$fyl")
    return (fyl && fyl.isFile() && fyl.canRead())
  }


  /** Locate the mappings file, load the mappings, and return them. */
  def loadMappings (File mapfile, options) {
    log.trace("(Extractor.loadMappings): mapfile=${mapfile}, options=${options}")
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
      log.info("(Extractor.loadMappings): Reading mappings from: ${mapName}")

    def inSR = new InputStreamReader(mapStream, 'UTF8')
    inSR.eachLine { line ->
      def fields = line.split(',')
      if (fields.size() == 2) {
        mappings.put(fields[0].trim(), fields[1].trim())
        mCnt += 1
      }
    }

    mappings.each { entry -> println("${entry.key}=${entry.value}") } // REMOVE LATER
    if (VERBOSE)
      log.info("(Extractor.main): Read ${mCnt} field mappings.")

    return mappings
  }


  /** Process the files in all subdirectories of the given top-level directory. */
  def processDirs (worker, topDirectory) {
    log.trace("(Extractor.processDirs): worker=${worker}, topDirectory=${topDirectory}")
    int cnt = processFiles(worker, topDirectory)
    topDirectory.eachDirRecurse { dir ->
      if (goodDirectory(dir)) {
        cnt += processFiles(worker, dir)
      }
    }
    return cnt
  }

  /** Process the files in the given directory. */
  def processFiles (worker, directory) {
    log.trace("(Extractor.processFiles): worker=${worker}, dir=${directory}")
    int cnt = 0
    def fileList = directory.list(this) as List
    fileList.each { filename ->
      def aFile = goodFile(directory, filename)
      if (!aFile)
        return                      // exit out now if not a valid file
      else {
        cnt += processAFile(worker, aFile)
      }
    }
    return cnt
  }

  /** Process the single given file with the given worker. */
  def processAFile (worker, aFile) {
    log.trace("(Extractor.processAFile): worker=${worker}, aFile=${aFile}")
    println("FILE: ${aFile.getName()}")     // REMOVE LATER
    // TODO: IMPLEMENT LATER
    return 1
  }

  /** Return a (possibly empty) list of valid file/directory paths. */
  def validatePathStrings (pathStrings) {
    log.trace("(Extractor.validatePathStrings): pathStrings=$pathStrings")
    pathStrings.findResults { pathname ->
      if (isAcceptableFilename(pathname))
        goodFilePath(pathname)
      else
        goodDirPath(pathname)
    }
  }

}


/** Temporary class for testing skeleton - REMOVE LATER. */
class Worker {
  static final Logger log = LogManager.getLogger(Worker.class.getName());

  Map settings                              // class global settings

  /** Public constructor taking a map of ingest options and an ElasticSearch loader class. */
  public Worker (settings) {
    log.trace("(Worker.init): settings=${settings}")
    this.settings = settings                // save incoming settings in global variable
  }
}
