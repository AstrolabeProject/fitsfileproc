package edu.arizona.astrolabe.ffp

import java.io.*
// import java.util.zip.GZIPInputStream
import org.apache.logging.log4j.*

/**
 * Astrolabe JWST-specific FITS file processor project.
 *   This class implements the data structure to hold information about a single field.
 *
 *   Written by: Tom Hicks. 8/20/2019.
 *   Last Modified: Initial creation: split from JWST processor class.
 */
class FieldInfo extends java.util.LinkedHashMap {
  static final Logger log = LogManager.getLogger(FieldInfo.class.getName());

  /** The key which identifies the value within the field information. */
  private static final String VALUE = 'value'

  /**
   * Return the current value for the named field, or null if the named field is
   * not present or if it does not have a current value.
   */
  private def getValue () {
    log.trace("(FieldInfo.getValue)")
    return this.get(VALUE)
  }

  /** Return true if the this field info has a data value, else return false. */
  private boolean hasValue () {
    log.trace("(FieldInfo.hasValue)")
    return (this.get(VALUE) != null)
  }

  /** Set the current value for this field to the given value (which could be null). */
  private void setValue (val) {
    log.trace("(FieldInfo.setValue): val=${val}")
    this.put(VALUE, val)
  }

}


/**
 * Class implementing the data structure to hold information about multiple fields,
 * keyed by the field keyword.
 */
class FieldsInfo extends java.util.LinkedHashMap {
  static final Logger log = LogManager.getLogger(FieldsInfo.class.getName());

  /**
   * Copy the current value from the named field to the other named field but only if
   * the "from" field exists and has a value and the "to" field information already exists.
   * Whether the existing "to" value is overwritten is determined by the value of the
   * overwrite field (default true).
   */
  private void copyValue (String fromKey, String toKey, boolean overwrite=true) {
    log.trace("(FieldsInfo.copyValue): from=${fromKey}, to=${toKey}, overwrite=${overwrite}")
    def toField = this.get(toKey)
    def fromField = this.get(fromKey)
    if (fromField.hasValue() && (toField != null)) { // From has a value and To exists
      if (overwrite || !toField.hasValue()) {        // dont replace value if overwrite is false
        toField.setValue(fromField.getValue())
      }
    }
  }

  /** Augment the get method to return an object of this type. */
  private FieldInfo get (String whichField) {
    log.trace("(FieldsInfo.get)")
    return (super.get(whichField) as FieldInfo)
  }

  /**
   * Return the current value for the named field, or null if the named field is
   * not present or if it does not have a current value.
   */
  private def getValueFor (String whichField) {
    log.trace("(FieldsInfo.getValueFor): whichField=${whichField}")
    def fieldInfo = this.get(whichField)
    return ((fieldInfo != null) ? fieldInfo.getValue() : null)
  }

  /** Return true if the named field is none-null and has a data value, else return false. */
  private boolean hasValueFor (String whichField) {
    log.trace("(FieldsInfo.hasValueFor): whichField=${whichField}")
    def fieldInfo = this.get(whichField)
    return ((fieldInfo != null) && (fieldInfo.getValue() != null))
  }

  /** Set the value of the named field to the given value. */
  private void setValueFor (String whichField, val) {
    log.trace("(FieldsInfo.setValueFor): whichField=${whichField}, val=${val}")
    def fieldInfo = this.get(whichField)
    if (fieldInfo != null)
      fieldInfo.setValue(val)
  }

}


/**
 * Factory Class which creates the data structure to hold information about multiple fields.
 */
class FieldsInfoFactory {
  static final Logger log = LogManager.getLogger(FieldsInfoFactory.class.getName());

  /** String which defines a comment line in the JWST resource files. */
  static final String COMMENT_MARKER = '#'

  /** String which defines the line containing column_names in the JWST resource files. */
  static final String COLUMN_NAME_MARKER = '_COLUMN_NAMES_'

  /** String which marks a field with "no default value" or a value to be calculated. */
  static final String NO_DEFAULT_VALUE = '*'

  /** String which defines a "not yet implemented" line in the JWST resource files. */
  static final String NOP_ENTRY_KEY = '_NOP_'


  /** Debug setting: when true, show internal information for debugging. */
  private boolean DEBUG   = false

  /** Verbose setting: when true, show extra information about program operation. */
  private boolean VERBOSE = false

  /** Configuration parameters given to this class in the constructor. */
  private Map config

  /** Default resource file for header field information. */
  private String defaultFieldsFilepath = '/obscore-fields.txt'

  /** List of column names in the header field information file (with default values). */
  private List fieldInfoColumnNames = [ 'obsCoreKey', 'datatype', 'required', 'default' ]


  /** Public constructor taking a map of configuration settings. */
  public FieldsInfoFactory (configuration) {
    log.trace("(FieldsInfoFactory.ctor): config=${configuration}")
    config = configuration                  // save incoming settings in global variable
    DEBUG = configuration?.DEBUG
    VERBOSE = configuration?.VERBOSE
    defaultFieldsFilepath = configuration?.defaultFieldsFilepath
    fieldInfoColumnNames = configuration?.fieldInfoColumnNames
  }


  /**
   * Read the file containing information about the fields processed by this program,
   * and return the fields in a map. Loads the field information from the given file
   * or a default resource path, if no file given.
   */
  public FieldsInfo loadFieldsInfo (File fieldsFile=null) {
    log.trace("(FieldsInfoFactory.loadFieldsInfo): fieldsFile=${fieldsFile}")
    def recCnt = 0
    def fields = new FieldsInfo()
    def fieldStream
    def fieldsFilepath = defaultFieldsFilepath

    if (fieldsFile) {                       // if given external fields file, use it
      fieldStream = new FileInputStream(fieldsFile)
      fieldsFilepath = fieldsFile.getAbsolutePath()
    }
    else                                    // else fallback to default resource
      fieldStream = this.getClass().getResourceAsStream(defaultFieldsFilepath);

    if (DEBUG)
      log.info("(FieldsInfoFactory.loadFieldsInfo): Reading field information from: ${fieldsFilepath}")

    def numInfoFields = fieldInfoColumnNames.size() // to avoid recomputation in loop

    def inSR = new InputStreamReader(fieldStream, 'UTF8')
    inSR.eachLine { line ->
      if (line.trim().isEmpty() || line.startsWith(COMMENT_MARKER)) {
        // ignore empty lines and comment lines
      }
      else if (line.startsWith(COLUMN_NAME_MARKER)) { // line is a column name (header) line
        def flds = line.split(',').collect{it.trim()}
        if (flds.size() > 2) {              // must be at least two column names
          fieldInfoColumnNames = flds[1..-1] // drop column_name_marker (first field)
        }
      }
      else {                                // assume line is a data line
        def flds = line.split(',').collect{it.trim()}
        if (flds && (flds.size() == numInfoFields)) {
          FieldInfo infoMap = [fieldInfoColumnNames, flds].transpose().collectEntries()
          fields.put(flds[0], infoMap)      // store all fields keyed by first field
          recCnt += 1
        }
      }
    }

    if (DEBUG)
      log.info("(FieldsInfoFactory.loadFieldsInfo): Read ${recCnt} field information records.")

    return fields
  }

}
