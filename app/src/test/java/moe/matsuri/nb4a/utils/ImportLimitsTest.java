package moe.matsuri.nb4a.utils;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public class ImportLimitsTest {
    @Test public void smallAndLargeReadsAndActualReadBoundary() throws Exception {
        for (int count : new int[]{100, 8*1024*1024}) {
            String result=ImportLimits.readUtf8(new ByteArrayInputStream(new byte[count]));assertEquals(count,result.length());
        }
        final int[] consumed={0};
        InputStream endless=new InputStream(){public int read(){consumed[0]++;return 'x';}};
        assertThrows(ImportLimits.InputLimitException.class,()->ImportLimits.readUtf8(endless,1024));
        assertEquals(1025,consumed[0]);
        assertEquals("ok",ImportLimits.readUtf8(new ByteArrayInputStream("ok".getBytes(StandardCharsets.UTF_8))));
    }
    @Test public void zlibExpansionAndMalformedInputAreBoundedAndRecoverable() throws Exception {
        ByteArrayOutputStream archive=new ByteArrayOutputStream();
        try(DeflaterOutputStream z=new DeflaterOutputStream(archive)) {
            byte[] chunk=new byte[8192];
            for(int i=0;i<ImportLimits.MAX_TEXT_BYTES/chunk.length+1;i++)z.write(chunk);
        }
        assertThrows(ImportLimits.InputLimitException.class,()->ImportLimits.inflate(archive.toByteArray()));
        for(byte[] bad:new byte[][]{new byte[0],new byte[]{1,2,3},Arrays.copyOf(archive.toByteArray(),8)})
            assertThrows(IOException.class,()->ImportLimits.inflate(bad));
        archive.reset();try(DeflaterOutputStream z=new DeflaterOutputStream(archive)){z.write("normal".getBytes(StandardCharsets.UTF_8));}
        assertArrayEquals("normal".getBytes(StandardCharsets.UTF_8),ImportLimits.inflate(archive.toByteArray()));
    }
    @Test public void jsonDepthUtf8AndCancellation() throws Exception {
        StringBuilder deep=new StringBuilder();for(int i=0;i<129;i++)deep.append('[');for(int i=0;i<129;i++)deep.append(']');
        assertThrows(ImportLimits.InputLimitException.class,()->ImportLimits.checkText(deep.toString()));
        ImportLimits.checkText("{\"s\":\"[[[\\\"{}]]]\",\"array\":[1,2]}");
        ImportLimits.checkText("[Interface]\nPrivateKey=synthetic\n[Peer]\nEndpoint=127.0.0.1:1");
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedIOException.class,()->ImportLimits.readUtf8(new ByteArrayInputStream(new byte[1]))); }
        finally {Thread.interrupted();}
        ImportLimits.checkNodeCount(50000);
        assertThrows(ImportLimits.InputLimitException.class,()->ImportLimits.checkNodeCount(50001));
    }
    @Test public void jsonTokenBudgetAndUtf8BudgetAreEnforcedBeforeParse() throws Exception {
        StringBuilder values=new StringBuilder("[");
        for(int i=0;i<2000001;i++) values.append("0,");
        values.append("0]");
        assertThrows(ImportLimits.InputLimitException.class,()->ImportLimits.checkText(values.toString()));
        char[] multi=new char[ImportLimits.MAX_TEXT_BYTES/3+1];Arrays.fill(multi,'中');
        assertThrows(ImportLimits.InputLimitException.class,()->ImportLimits.checkText(new String(multi)));
        ImportLimits.checkText("{\"recovery\":true}");
    }
    private byte[] zip(int count,int entrySize) throws Exception {
        ByteArrayOutputStream result=new ByteArrayOutputStream();
        try(ZipOutputStream z=new ZipOutputStream(result)) {
            byte[] chunk=new byte[8192];
            for(int i=0;i<count;i++) {z.putNextEntry(new ZipEntry(i+".conf"));int left=entrySize;
                while(left>0){int n=Math.min(left,chunk.length);z.write(chunk,0,n);left-=n;}z.closeEntry();}
        } return result.toByteArray();
    }
    @Test public void zipEntryCountPerEntryAndTotalExpansion() throws Exception {
        try(ImportLimits.ZipReader r=new ImportLimits.ZipReader(new ByteArrayInputStream(zip(2,10)))) {
            assertEquals("0.conf",r.next().name);assertEquals(10,r.next().text.length());assertNull(r.next());
        }
        byte[] tooMany=zip(1025,0);
        assertThrows(ImportLimits.InputLimitException.class,()->{try(ImportLimits.ZipReader r=new ImportLimits.ZipReader(new ByteArrayInputStream(tooMany))){while(r.next()!=null){}}});
        byte[] huge=zip(1,ImportLimits.MAX_TEXT_BYTES+1);
        assertThrows(ImportLimits.InputLimitException.class,()->{try(ImportLimits.ZipReader r=new ImportLimits.ZipReader(new ByteArrayInputStream(huge))){r.next();}});
        byte[] expanded=zip(3,24*1024*1024);
        assertThrows(ImportLimits.InputLimitException.class,()->{try(ImportLimits.ZipReader r=new ImportLimits.ZipReader(new ByteArrayInputStream(expanded))){while(r.next()!=null){}}});
    }
    @Test public void duplicateNamesKeepLegacySequenceWithoutQuadraticRescanning() throws Exception {
        ImportLimits.Names names=new ImportLimits.Names();
        assertEquals("node",names.allocate("node"));
        for(int i=1;i<10000;i++)assertEquals("node ("+i+")",names.allocate("node"));
        String[] inputs={"n (1)","n","n","n (0)","n (1)","n (1)","n"};
        names=new ImportLimits.Names();Set<String> old=new HashSet<>();
        for(String base:inputs) {String candidate=base;int index=0;while(old.contains(candidate)){index++;candidate=candidate.replace(" ("+(index-1)+")","")+" ("+index+")";}old.add(candidate);assertEquals(candidate,names.allocate(base));}
    }
}
