package edu.arizona.astrolabe.extractor

import java.io.*
// import java.util.zip.GZIPInputStream

import org.apache.logging.log4j.*

/**
 * General file utilities for checking for and validating file paths.
 *   All methods in this class are static and may be called directly.
 *
 *   Written by: Tom Hicks. 7/28/2019.
 *   Last Modified: Split out from main module.
 */
class FileUtils  {

  static final Logger log = LogManager.getLogger(FileUtils.class.getName());

  /** Return the basename of the given filename string. */
  public static String fileBasename (String filename) {
    log.trace("(FileUtils.fileBasename): filename=$filename")
    return filename.substring(0,filename.indexOf('.'))
  }

  /** Return true if the given file is a directory, readable and, optionally, writeable. */
  public static boolean  goodDirectory (File dir, Boolean writeable=false) {
    log.trace("(FileUtils.goodDirectory): dir=$dir")
    return (dir && dir.isDirectory() && dir.canRead() && (!writeable || dir.canWrite()))
  }

  /** If first argument is a path string to a readable directory return it else return null. */
  public static File goodDirPath (String dirPath, Boolean writeable=false) {
    log.trace("(FileUtils.goodDirPath): dirPath=$dirPath")
    if (dirPath.isEmpty())                  // sanity check
      return null
    def dir = new File(dirPath)
    return (goodDirectory(dir) ? dir : null)
  }

  /** If given file references a readable file return the file else return null. */
  public static File goodFile (File fyl) {
    log.trace("(FileUtils.goodFile): file=${fyl}")
    return (readableFile(fyl)) ? fyl : null
  }

  /** If given filename string references a readable file return the file else return null. */
  public static File goodFile (File directory, String filename) {
    log.trace("(FileUtils.goodFile): directory=${directory}, filename=${filename}")
    def fyl = new File(directory, filename)
    return (readableFile(fyl)) ? fyl : null
  }

  /** If given file path string references a readable file return the file else return null. */
  public static File goodFilePath (String filePath) {
    log.trace("(FileUtils.goodFilePath): filePath=$filePath")
    if (filePath.isEmpty())                  // sanity check
      return null
    def fyl = new File(filePath)
    return (readableFile(fyl) ? fyl : null)
  }

  /** Return true if the given file is a file and readable. */
  public static readableFile (File fyl) {
    log.trace("(FileUtils.readableFile): fyl=$fyl")
    return (fyl && fyl.isFile() && fyl.canRead())
  }

}
