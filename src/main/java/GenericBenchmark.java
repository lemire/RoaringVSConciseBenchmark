import it.uniroma3.mat.extendedset.intset.ConciseSet;
import it.uniroma3.mat.extendedset.intset.ImmutableConciseSet;
import it.uniroma3.mat.extendedset.intset.IntSet.IntIterator;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;

import net.sourceforge.sizeof.SizeOf;

import org.roaringbitmap.buffer.BufferFastAggregation;
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;
import org.roaringbitmap.RoaringBitmap;

public class GenericBenchmark {

    private static final int nbRepetitions = 100;
    private static final long warmup_ms = 100L;
    private static int careof = 0;
    private static ImmutableRoaringBitmap[] irbs = null;
    private static ArrayList<ImmutableConciseSet> icss = null;
    private static ImmutableRoaringBitmap irb = null;
    private static ImmutableConciseSet ics = null;

    public static int numberOfRuns(int[] datapoints) {
      int high = -1000;
      int run = 0;
      for(int k = 0; k<datapoints.length; ++k) {
        if(datapoints[k] != high +1 ) ++run;
        high = datapoints[k];
      }
      return run;
}

    public static void main(String[] args) {
        boolean sizeOf = true;
        try {
            SizeOf.setMinSizeToLog(0);
            SizeOf.skipStaticField(true);
            SizeOf.deepSizeOf(args);
        } catch (IllegalStateException e) {
            sizeOf = false;
            System.out
                    .println("# disabling sizeOf, run  -javaagent:lib/SizeOf.jar or equiv. to enable");
        }

        try {
        	ArrayList<int[]> datum = new ArrayList<int[]>();
        	File folder  = new File(args.length == 0 ? "real-roaring-datasets" : args[0]);
          File[] listOfFiles = folder.listFiles();
          long numberofruns = 0;
          RealDataRetriever dataRetriever = new RealDataRetriever(folder);

                // ************ Roaring part ****************
                {
                    File file = File.createTempFile("roarings", "bin");
                    file.deleteOnExit();
                    final FileOutputStream fos = new FileOutputStream(file);
                    final DataOutputStream dos = new DataOutputStream(fos);
                    ArrayList<Long> offsets = new ArrayList<Long>();
                    // Building NumberOfBitmaps RoaringBitmaps
                    long bef = System.currentTimeMillis();
                    for(File datafile : folder.listFiles()) {
                    	 try{
                        int[] data = dataRetriever
                                .fetchBitPositions(datafile);
                        numberofruns += numberOfRuns(data);
                        datum.add(data.clone());
                        RoaringBitmap rb = RoaringBitmap.bitmapOf(data);
                        rb.trim();
                        offsets.add(fos.getChannel().position());
                        rb.serialize(dos);
                        dos.flush();
                    	 } catch (Exception e) {
                    		 System.out.println("Ignoring file "+datafile);
                    	 }
                    }
                    offsets.add(fos.getChannel().position());
                    long aft = System.currentTimeMillis();
                    long serialisationTime = aft - bef;
                    long lastOffset = fos.getChannel().position();
                    dos.close();
                    RandomAccessFile memoryMappedFile = new RandomAccessFile(
                            file, "r");
                    MappedByteBuffer mbb = memoryMappedFile.getChannel().map(
                            FileChannel.MapMode.READ_ONLY, 0, lastOffset);
                    // RAM space used in bytes
                    long sizeRAM = 0;
                    irbs = new ImmutableRoaringBitmap[datum.size()];
                    for (int k = 0; k < offsets.size() - 1; k++) {
                        mbb.position((int) offsets.get(k).longValue());
                        final ByteBuffer bb = mbb.slice();
                        bb.limit((int) (offsets.get(k + 1) - offsets.get(k)));
                        ImmutableRoaringBitmap irb = new ImmutableRoaringBitmap(
                                bb);
                        irbs[k] = irb;
                        if (sizeOf)
                            sizeRAM += (SizeOf.deepSizeOf(irb));
                    }
                    // we redo the work, but just for the timing
                    bef = System.currentTimeMillis();
                    for (int k = 0; k < offsets.size() - 1; k++) {
                        mbb.position((int) offsets.get(k).longValue());
                        final ByteBuffer bb = mbb.slice();
                        bb.limit((int) (offsets.get(k + 1) - offsets.get(k)));
                        ImmutableRoaringBitmap irb = new ImmutableRoaringBitmap(
                                bb);
                        irbs[k] = irb;
                    }
                    aft = System.currentTimeMillis();
                    long deserializationTime = aft - bef;
                    //irbs = Arrays.copyOfRange(irbs, 0, offsets.size() - 1);
                    // Disk space used in bytes
                    long sizeDisk = file.length();
                    // Horizontal unions between NumberOfBitmaps Roaring bitmaps
                    double horizUnionTime = test(new Launcher() {
                        @Override
                        public void launch() {
                            irb = BufferFastAggregation.horizontal_or(irbs);
                            careof += irb.getCardinality();
                        }
                    });
                    // Intersections between NumberOfBitmaps Roaring bitmaps
                    double intersectTime = test(new Launcher() {
                        @Override
                        public void launch() {
                            irb = BufferFastAggregation.and(irbs);
                            careof += irb.getCardinality();
                        }
                    });
                    // Average time to retrieve set bits
                    double scanTime = testScanRoaring();
                    System.out.println("***************************");
                    System.out.println("Roaring bitmap on ");
                    System.out.println("***************************");
                    System.out.println("Deserialization time: "+deserializationTime+" ms");
                    System.out.println("RAM Size = " + (sizeRAM*1. / 1024)
                            + " Kb" + " ("
                            + Math.round(sizeRAM * 1. / datum.size())
                            + " bytes/bitmap)");
                    System.out.println("Disk Size = " + (sizeDisk*1. / 1024)
                            + " Kb" + " ("
                            + Math.round(sizeDisk * 1. / datum.size())
                            + " bytes/bitmap)");
                    System.out.println("Number of Runs = "+numberofruns);
                    System.out.println("Number of Bitmaps = "+datum.size());
                    System.out.println("Horizontal unions time = "
                            + horizUnionTime + " ms");
                    System.out.println("Intersections time = " + intersectTime
                    		+ " ms");
                    System.out.println("Scans time = " + scanTime + " ms");
                    System.out.println(".ignore = " + careof);
                    mbb = null;
                    memoryMappedFile.close();
                    file.delete();
                }
                // ***************** ConciseSet part
                // **********************************
                {
                    File file = File.createTempFile("conciseSets", "bin");
                    file.deleteOnExit();
                    final FileOutputStream fos = new FileOutputStream(file);
                    final DataOutputStream dos = new DataOutputStream(fos);
                    ArrayList<Long> offsets = new ArrayList<Long>();
                    // Building NumberOfBitmaps ConciseSets
                    long bef = System.currentTimeMillis();
                    for (int j = 0; j < datum.size(); j++) {
                        ConciseSet cs = toConcise((int[])datum.get(j));
                        offsets.add(fos.getChannel().position());
                        int[] ints = cs.getWords();
                        for (int k = 0; k < ints.length; k++)
                            dos.writeInt(ints[k]);
                        dos.flush();
                    }
                    offsets.add(fos.getChannel().position());
                    long aft = System.currentTimeMillis();
                    long serialisationTime = aft - bef;
                    long lastOffset = fos.getChannel().position();
                    dos.close();
                    // RAM storage in bytes
                    long sizeRAM = 0;
                    RandomAccessFile memoryMappedFile = new RandomAccessFile(
                            file, "r");
                    MappedByteBuffer mbb = memoryMappedFile.getChannel().map(
                            FileChannel.MapMode.READ_ONLY, 0, lastOffset);
                    icss = new ArrayList<ImmutableConciseSet>();
                    for (int k = 0; k < offsets.size() - 1 ; k++) {
                        mbb.position((int) offsets.get(k).longValue());
                        final ByteBuffer bb = mbb.slice();
                        bb.limit((int) (offsets.get(k + 1) - offsets.get(k)));
                        ImmutableConciseSet ics = new ImmutableConciseSet(bb);
                        icss.add(ics);
                        if (sizeOf)
                          sizeRAM += (SizeOf.deepSizeOf(ics));
                    }
                    bef = System.currentTimeMillis();
                    icss = new ArrayList<ImmutableConciseSet>(datum.size());
                    for (int k = 0; k < offsets.size() - 1 ; k++) {
                        mbb.position((int) offsets.get(k).longValue());
                        final ByteBuffer bb = mbb.slice();
                        bb.limit((int) (offsets.get(k + 1) - offsets.get(k)));
                        ImmutableConciseSet ics = new ImmutableConciseSet(bb);
                        icss.add(ics);
                    }
                    aft = System.currentTimeMillis();
                    long deserializationTime = aft - bef;
                    // Disk storage in bytes
                    long sizeDisk = file.length();
                    // Average time to compute unions between NumberOfBitmaps
                    // ConciseSets
                    
                    double unionTime = test(new Launcher() {
                        @Override
                        public void launch() {
                            ics = ImmutableConciseSet.union(icss.iterator());
                            careof += ics.size();
                        }
                    });
                    // Average time to compute intersects between
                    // NumberOfBitmaps ConciseSets
                    double intersectTime = test(new Launcher() {
                        @Override
                        public void launch() {
                            ics = ImmutableConciseSet.intersection(icss
                                    .iterator());
                            careof += ics.size();
                        }
                    });
                    // Average time to retrieve set bits
                    double scanTime = testScanConcise();

                    System.out.println("***************************");
                    System.out.println("ConciseSet on  dataset");
                    System.out.println("***************************");
                    System.out.println("Deserialization time: "+deserializationTime+" ms");
                    System.out.println("RAM Size = " + (sizeRAM * 1. / 1024)
                            + " Kb" + " ("
                            + Math.round(sizeRAM * 1. / datum.size())
                            + " bytes/bitmap)");
                    System.out.println("Disk Size = " + (sizeDisk * 1. / 1024)
                            + " Kb" + " ("
                            + Math.round(sizeDisk * 1. / datum.size())
                            + " bytes/bitmap)");
                    System.out.println("Number of Runs = "+numberofruns);
                    System.out.println("Number of Bitmaps = "+datum.size());
                    System.out.println("Unions time = " + unionTime + " ms");
                    System.out.println("Intersections time = " + intersectTime
                            + " ms");
                    System.out.println("Scans time = " + scanTime + " ms");
                    System.out.println(".ignore = " + careof);
                    mbb = null;
                    memoryMappedFile.close();
                    file.delete();
                }
          //  }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static ConciseSet toConcise(int[] dat) {
        ConciseSet ans = new ConciseSet();
        for (int i : dat) {
            ans.add(i);
        }
        return ans;
    }

    static double test(Launcher job) {
        long jobTime, begin, end;
        int i, repeat = 1;
        // Warming up the cache
        do {
            repeat *= 2;// potentially unsafe for very large integers
            begin = System.currentTimeMillis();
            for (int r = 0; r < repeat; r++) {
                job.launch();
            }
            end = System.currentTimeMillis();
            jobTime = (end - begin);
        } while ((jobTime < warmup_ms) && (repeat < (1 << 24)));
        // We can start timings now
        begin = System.currentTimeMillis();
        for (i = 0; i < nbRepetitions; ++i) {
            job.launch();
        }
        end = System.currentTimeMillis();
        jobTime = end - begin;
        return (double) (jobTime) / (double) (nbRepetitions);
    }

    static double testScanRoaring() {
        long scanTime, begin, end;
        int i, k, repeat = 1;
        org.roaringbitmap.IntIterator it;
        // Warming up the cache
        do {
            repeat *= 2;
            scanTime = 0;
            for (int r = 0; r < repeat; r++) {
                begin = System.currentTimeMillis();
                for (k = 0; k < irbs.length; k++) {
                    irb = irbs[k];
                    it = irb.getIntIterator();
                    while (it.hasNext()) {
                        it.next();
                    }
                }
                end = System.currentTimeMillis();
                scanTime += end - begin;
            }
        } while ((scanTime < warmup_ms) && (repeat < (1 << 24)));

        // We can start timings now
        scanTime = 0;
        for (i = 0; i < nbRepetitions; i++) {
            begin = System.currentTimeMillis();
            for (k = 0; k < irbs.length; k++) {
                irb = irbs[k];
                it = irb.getIntIterator();
                while (it.hasNext()) {
                    it.next();
                }
            }
            end = System.currentTimeMillis();
            scanTime += end - begin;
        }
        return scanTime * 1. / nbRepetitions;
    }

    static double testScanConcise() {
        long scanTime, begin, end;
        int i, k, repeat = 1;
        IntIterator it;
        // Warming up the cache
        do {
            repeat *= 2;
            scanTime = 0;
            for (int r = 0; r < repeat; r++) {
                begin = System.currentTimeMillis();
                for (k = 0; k < icss.size(); k++) {
                    ics = icss.get(k);
                    it = ics.iterator();
                    while (it.hasNext()) {
                        it.next();
                    }
                }
                end = System.currentTimeMillis();
                scanTime += end - begin;
            }
        } while ((scanTime < warmup_ms) && (repeat < (1 << 24)));

        // We can start timings now
        scanTime = 0;
        begin = System.currentTimeMillis();
        for (i = 0; i < nbRepetitions; i++) {
            for (k = 0; k < icss.size(); k++) {
                ics = icss.get(k);
                it = ics.iterator();
                while (it.hasNext()) {
                    it.next();
                }
            }
        }
        end = System.currentTimeMillis();
        scanTime = end - begin;
        return scanTime * 1. / nbRepetitions;
    }

}
