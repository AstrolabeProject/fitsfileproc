## Astrolabe FITS file metadata extractor tool.

This is a public code repository of the [Astrolabe Project](http://astrolabe.arizona.edu/) at the [University of Arizona](http://www.arizona.edu).

Author: [Tom Hicks](https://github.com/hickst)

Purpose: Extracts specific metadata from FITS files for the purpose of creating a VO-compliant database.

## Installation

This software requires Java 1.8, Gradle 5.1, Groovy 2.5.7+.

To build the standalone JAR file in the build/libs subdirectory:

```
   > gradle clean shadowJar
```

To run the JAR file:

```
   > java -jar extractor-0.0.2.jar -v /input/dir/path
```

Run Options:

```
Usage: extractor [-h] [-m mapfilepath] directory
 -d,--debug               Print debugging output in addition to normal processing (default false).
 -h,--help                Show usage information.
 -m,--mapfile <mapfile>   File containing FITS fieldname mappings.
 -v,--verbose             Run in verbose mode (default: non-verbose).
```

## License

Licensed under Apache License Version 2.0.

(c) The University of Arizona, 2019
