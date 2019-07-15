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
 *   Last Modified: Activate filename filtering skeleton.
 */
class Extractor implements FilenameFilter {

  static final Logger log = LogManager.getLogger(Extractor.class.getName());

  static final String DEFAULT_MAP_FILEPATH = "/default-mappings.txt"

  public boolean DEBUG   = false
  public boolean VERBOSE = false
  public Map MAPPINGS = [:]

  /** Main program entry point. */
  public static void main (String[] args) {
    // read, parse, and validate command line arguments
    def usage = 'extractor [-h] [-m mapfilepath] directory'
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

    // validate the input directory path
    File inDir = xtor.goodDirPath(options.arguments()[0])
    if (!inDir) {
      System.err.println("Unable to open input directory ${inDir} for reading...exiting")
      System.exit(2)                        // problem with input directory: exit out now
    }

    // find and open the specified mappings file or use the default one
    def mapfile = options.m
    if (mapfile) {
      File mappings = xtor.goodFile(mapfile)
      if (!mappings) {
        System.err.println("Unable to open mappings file '${mapfile}' ...exiting")
        System.exit(3)
      }
    }
    else
      mapfile = DEFAULT_MAP_FILEPATH

    if (xtor.VERBOSE)
      log.info("(Extractor.main): Reading mapping file: ${mapfile}...")
    def mCnt = xtor.loadMappings(mapfile)
    xtor.MAPPINGS.each { entry -> println("${entry.key}=${entry.value}") } // REMOVE LATER
    if (xtor.VERBOSE)
      log.info("(Extractor.main): Read ${mCnt} filename mappings.")

    // create worker with the specified settings
    def settings = [ 'DEBUG':    xtor.DEBUG,
                     'MAPPINGS': xtor.MAPPINGS,
                     'VERBOSE':  xtor.VERBOSE ]
    def worker = new Worker(settings)

    // transform and load the result files in the directory
    if (xtor.VERBOSE) {
      log.info("(Extractor.main): Processing result files from ${inDir}...")
    }

    def procCount = xtor.processDirs(worker, inDir)
    // worker.exit()                           // cleanup worker
    if (xtor.VERBOSE)
      log.info("(Extractor.main): Processed ${procCount} results.")
  }


  /** Public constructor taking a map of ingest options. */
  public Extractor (options) {
    log.trace("(Extractor.init): options=${options}")
    VERBOSE = options.v ?: false
    DEBUG = options.d ?: false
  }


  /** This class implements java.io.FilenameFilter with this method. */
  boolean accept (java.io.File dir, java.lang.String filename) {
    // return FILE_TYPES.any { filename.endsWith(it) } // more selective file types
    return filename.endsWith('.fits')
  }

  /** Return the basename of the given filename string. */
  def fileBasename (filename) {
    return filename.substring(0,filename.indexOf('.'))
  }

  /** Return true if the given file is a directory, readable and, optionally, writeable. */
  def goodDirectory (File dir, writeable=false) {
    return (dir && dir.isDirectory() && dir.canRead() && (!writeable || dir.canWrite()))
  }

  /** If first argument is a path string to a readable directory return it else return null. */
  File goodDirPath (dirPath, writeable=false) {
    if (dirPath.isEmpty())                  // sanity check
      return null
    def dir = new File(dirPath)
    return (goodDirectory(dir) ? dir : null)
  }

  /** If given filename string references a readable file in the given directory,
      return the file else return null. */
  File goodFile (File directory, String filename) {
    def fyl = new File(directory, filename)
    return (fyl && fyl.isFile() && fyl.canRead()) ? fyl : null
  }

  /** If given file path string references a readable file return the file else return null. */
  File goodFile (String filepath) {
    def fyl = new File(filepath)
    return (fyl && fyl.isFile() && fyl.canRead()) ? fyl : null
  }

  /** Load the mapping from disk and return a count of the mappings read. */
  def loadMappings (mapFilePath) {
    def cnt = 0
    def mapStream = this.getClass().getResourceAsStream(mapFilePath);
    def inSR = new InputStreamReader(mapStream, 'UTF8')
    inSR.eachLine { line ->
      def fields = line.split(',')
      if (fields.size() == 2) {
        MAPPINGS.put(fields[0].trim(), fields[1].trim())
        cnt += 1
      }
    }
    return cnt
  }

  /** Process the files in all subdirectories of the given top-level directory. */
  def processDirs (worker, topDirectory) {
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
      def fyl = goodFile(directory, filename)
      if (!fyl)
        return                      // exit out now if not a valid file
      // else TODO: IMPLEMENT LATER
      println("FILE: ${fyl.getName()}")     // REMOVE LATER
    }
    return cnt
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
