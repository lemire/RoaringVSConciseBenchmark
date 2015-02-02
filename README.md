MemoryMappedBitmaps
===================

Real data experiments that compare performances of Roaring bitmap 2.0 and ConciseSet 2.2 using memory mapped files.

Usage 
===================
* install java
* install maven 2
* execute : mvn package   (alternatively: mvn -Dmaven.test.skip=true package)
* then : cd target
* then : java -cp MemoryMappedFiles-0.0.1-SNAPSHOT.jar:lib/* -javaagent:./lib/SizeOf.jar Benchmark ../real-roaring-datasets


To run a benchmark on a directory containing text files representing entries in a bitmap (each data file should be a single line containing a comma-separated list of values), run:

          java -cp MemoryMappedFiles-0.0.1-SNAPSHOT.jar:lib/* -javaagent:./lib/SizeOf.jar GenericBenchmark myfolder

