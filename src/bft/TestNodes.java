/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft;

import bftsmart.tom.AsynchServiceProxy;
import bftsmart.tom.RequestContext;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.core.messages.TOMMessageType;
import bftsmart.tom.util.Storage;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.hyperledger.fabric.protos.common.Common;

/**
 *
 * @author joao
 */
public class TestNodes {
        
    private ProxyReplyListener listener;
    private AsynchServiceProxy proxy;
    private ExecutorService executor;
    private BlockThread blockThread;
            
    public static int initID;

    public static void main(String[] args) throws Exception{

        if(args.length < 6) {
            System.out.println("Use: java TestNodes <init ID> <num clients> <delay> <envelope payload size> <add signature?> <block batch>");
            System.exit(-1);
        }      
        
        new TestNodes(args);
        
    }
    
    public TestNodes(String[] args) throws IOException, NoSuchAlgorithmException, NoSuchProviderException {
        
        TestNodes.initID = Integer.parseInt(args[0]);
        int clients = Integer.parseInt(args[1]);
        int delay = Integer.parseInt(args[2]);
        int batch = Integer.parseInt(args[5]);
        
        Lock[] inputLock = new Lock[clients];
        Condition[] windowAvailable = new Condition[clients];
        for (int i = 0; i < clients; i++) {
            
            inputLock[i] = new ReentrantLock();
            windowAvailable[i] = inputLock[i].newCondition();
        }
        
        this.proxy = new AsynchServiceProxy(TestNodes.initID, BFTNode.BFTSMART_CONFIG_FOLDER);
        
        this.listener = new ProxyReplyListener(this.proxy.getViewManager(), inputLock, windowAvailable);
        this.proxy.getCommunicationSystem().setReplyReceiver(this.listener);
        
        int reqId = this.proxy.invokeAsynchRequest(serializeBatchParams(batch), null, TOMMessageType.ORDERED_REQUEST);
        this.proxy.cleanAsynchRequest(reqId);
        reqId = proxy.invokeAsynchRequest(createGenesisBlock().toByteArray(), null, TOMMessageType.ORDERED_REQUEST);
        this.proxy.cleanAsynchRequest(reqId);
                    
        this.blockThread = new BlockThread(TestNodes.initID, this.listener);
        this.blockThread.start();
            
        this.executor = Executors.newCachedThreadPool(); 
        
        System.out.println("Waiting 15 seconds...");
        try
        {
            //System.in.read();
            Thread.sleep(15000);
        }  
        catch(Exception e)
        {}  
        
        for (int i = 0; i < clients; i++) {
        
            this.executor.execute(new ProxyThread(i + TestNodes.initID + 1, Integer.parseInt(args[3]),Boolean.parseBoolean(args[4]), delay, inputLock[i], windowAvailable[i]));
        
        }
        
    }
            
    public static byte[] serializeBatchParams(int batch) throws IOException {

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutput out = new ObjectOutputStream(bos);
        out.writeLong(524288);
        out.writeLong(batch);
        out.flush();
        bos.flush();
        out.close();
        bos.close();
        return bos.toByteArray();
    }
    
    public static Common.Block createGenesisBlock() throws NoSuchAlgorithmException, NoSuchProviderException {
        
        //initialize
        Common.BlockHeader.Builder blockHeaderBuilder = Common.BlockHeader.newBuilder();
        Common.BlockData.Builder blockDataBuilder = Common.BlockData.newBuilder();
        Common.BlockMetadata.Builder blockMetadataBuilder = Common.BlockMetadata.newBuilder();
        Common.Block.Builder blockBuilder = Common.Block.newBuilder();
                
        //create header
        blockHeaderBuilder.setNumber(0);
        blockHeaderBuilder.setPreviousHash(ByteString.EMPTY);
        blockHeaderBuilder.setDataHash(ByteString.EMPTY);
        
        //create metadata
        int numIndexes = Common.BlockMetadataIndex.values().length;
        for (int i = 0; i < numIndexes; i++) blockMetadataBuilder.addMetadata(ByteString.EMPTY);

        //crete block
        blockBuilder.setHeader(blockHeaderBuilder.build());
        blockBuilder.setMetadata(blockMetadataBuilder.build());
        blockBuilder.setData(blockDataBuilder.build());
        
        return blockBuilder.build();
    }
    
    private class ProxyThread implements Runnable {
                
        private int id;
        private int payloadSize;
        private boolean addSig;
        private int delay;
        private AsynchServiceProxy proxy;
        
        private int lastSeqNumber = -1;
        private final Lock inputLock;
        private final Condition windowAvailable;
        private boolean windowFull = false;
        private Random rand;
        private Storage latency = null;
         
        public ProxyThread (int id, int payloadSize, boolean addSig, int delay, Lock inputLock, Condition windowAvailable) {
            this.id = id;
            this.payloadSize = payloadSize;
            this.addSig = addSig;
            this.delay = delay;
            
            this.inputLock = inputLock;
            this.windowAvailable = windowAvailable;
            
            this.proxy = new AsynchServiceProxy(this.id, BFTNode.BFTSMART_CONFIG_FOLDER);
            
            rand = new Random(System.nanoTime());
                        
            /*this.proxy.getCommunicationSystem().setReplyReceiver((TOMMessage tomm) -> {
                //do nothing
            });*/

        }

        @Override
        public void run() {
                

                while (true) {
                
                                        
                    if (((this.lastSeqNumber+1) % BFTNode.REQUEST_WINDOW) == 0 && this.lastSeqNumber > 0) windowFull = true;
                    
                    int size = Math.max(Integer.BYTES + Long.BYTES, this.payloadSize);                    

                    ByteBuffer buff = ByteBuffer.allocate(size);
                    
                    buff.putInt(TestNodes.initID);
                    buff.putLong(System.nanoTime());
                    
                    while (buff.hasRemaining()) {
                        
                        buff.put((byte) this.rand.nextInt());
                    }
                    
                    byte[] signature;

                    if (this.addSig) {

                        signature = new byte[72];
                        rand.nextBytes(signature);

                    } else {
                        signature = new byte[0];
                    }

                    Common.Envelope.Builder builder = Common.Envelope.newBuilder();

                    builder.setPayload(ByteString.copyFrom(buff.array()));
                    builder.setSignature(ByteString.copyFrom(signature));

                    Common.Envelope env = builder.build();
            
                    this.lastSeqNumber = proxy.invokeAsynchRequest(env.toByteArray(), null, TOMMessageType.ORDERED_REQUEST);
                    
                    this.inputLock.lock();
                    
                    if (windowFull) {
                        
                        System.out.println("Window of client " + id + " is full");
                        
                        while (listener.getRemainingEnvs() > 10000) {
                            
                            try {
                                this.windowAvailable.awaitNanos(1000);
                            } catch (InterruptedException ex) {
                                ex.printStackTrace();
                            }
                        }
                        System.out.println("Window of client " + id + " is available");
                        
                    }
                    
                    this.inputLock.unlock();
                    
                    proxy.cleanAsynchRequest(this.lastSeqNumber);
                    
                    try {
                        if (this.delay > 0) Thread.sleep(this.delay);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        
    }
    
    class BlockThread extends Thread {

        private int id;
        private Storage latency = null;
        private ProxyReplyListener listener;
        
        public BlockThread(int id, ProxyReplyListener listener) {

            this.id = id;
            this.latency = new Storage(100000);
            this.listener = listener;
            
            this.setName("BlockThread-"+id);

        }

        public void run() {

            while (true) {

                Common.Block block = this.listener.getNext();
                                
                List<ByteString> data = block.getData().getDataList();
                    
                for (ByteString env : data) {

                    try {

                        ByteBuffer payload = ByteBuffer.wrap(Common.Envelope.parseFrom(env).getPayload().toByteArray());

                        int id = payload.getInt();
                        
                        if (id == TestNodes.initID) {

                            long time = payload.getLong();

                            latency.store(System.nanoTime() - time);

                            if (latency.getCount() == 100000) {

                                System.out.println("[latency] " + this.id + " // Average = " + (long) this.latency.getAverage(false) / 1000 + " (+/- "+ this.latency.getDP(false) +") us ");
                                System.out.println("[latency] " + this.id + " // Median  = " + this.latency.getPercentile(0.5) / 1000 + " us ");
                                System.out.println("[latency] " + this.id + " // 90th p  = " + this.latency.getPercentile(0.9) / 1000 + " us ");
                                System.out.println("[latency] " + this.id + " // 95th p  = " + this.latency.getPercentile(0.95) / 1000 + " us ");
                                System.out.println("[latency] " + this.id + " // Max     = " + this.latency.getMax(false) / 1000 + " us ");

                                latency.reset();
                            }
                        }

                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }

    }
}
