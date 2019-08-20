package edu.arizona.astrolabe.ffp

import org.apache.logging.log4j.*

/**
 * Astrolabe JWST-specific FITS file processor project.
 *   This class implements general, shared utility methods (all public static).
 *
 *   Written by: Tom Hicks. 8/20/2019.
 *   Last Modified: Initial creation: split from JWST processor class.
 */
class Utils {
  static final Logger log = LogManager.getLogger(Utils.class.getName());

  /**
   * Log the given error message from the named method. Output the error to
   * the standard error stream if the final argument is true (the default).
   */
  public static void logError (String fromWhere, String msg, boolean toSysErr=true) {
    log.error("(${fromWhere}): ${msg}")
    if (toSysErr)
      System.err.println("ERROR: (${fromWhere}): ${msg}")
  }

  /**
   * Log the given warning message from the named method. Output the warning to
   * the standard error stream if the final argument is true (the default).
   */
  public static void logWarning (String fromWhere, String msg, boolean toSysErr=true) {
    log.warn("(${fromWhere}): ${msg}")
    if (toSysErr)
      System.err.println("WARNING: (${fromWhere}): ${msg}")
  }

}
