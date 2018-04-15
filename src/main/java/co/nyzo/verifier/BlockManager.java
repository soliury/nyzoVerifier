package co.nyzo.verifier;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class BlockManager {

    public static final File blockRootDirectory = new File(Verifier.dataRootDirectory, "blocks");
    private static final AtomicLong highestBlockFrozen = new AtomicLong(-1L);
    private static final AtomicLong nextNewVerifierMinimumHeight = new AtomicLong(-1L);
    private static final long blocksPerFile = 1000L;
    private static final long filesPerDirectory = 1000L;

    static {
        initialize();
    }

    public static long highestBlockFrozen() {
        return highestBlockFrozen.get();
    }

    public static long nextNewVerifierMinimumHeight() {
        return nextNewVerifierMinimumHeight.get();
    }

    public static Block frozenBlockForHeight(long blockHeight) {

        // For a block that should be available, the map is checked first, then local files.
        Block block = null;
        if (blockHeight <= highestBlockFrozen.get()) {

            block = BlockManagerMap.blockForHeight(blockHeight);
            if (block == null) {
                loadBlockFromFile(blockHeight);
                block = BlockManagerMap.blockForHeight(blockHeight);

                // We used to have block loading from the network here. This could cause some serious performance issues
                // for verifiers, though, because we might have very large chains that need to be connected in order
                // to establish the veracity of a block.  So, we will instead allow verifiers to be ignorant of certain
                // parts of the chain. When a verifier starts, it will get the recent chain. If a transaction references
                // a hash of a block that a verifier does not know, the verifier should omit that transaction from
                // the block.
            }
        }

        return block;
    }

    public static List<Block> blocksInFile(File file, boolean addBlocksToCache) {

        List<Block> blocks = new ArrayList<>();
        Path path = Paths.get(file.getAbsolutePath());
        try {
            byte[] fileBytes = Files.readAllBytes(path);
            ByteBuffer buffer = ByteBuffer.wrap(fileBytes);
            int numberOfBlocks = buffer.getShort();
            Block previousBlock = null;
            for (int i = 0; i < numberOfBlocks; i++) {
                Block block = Block.fromByteBuffer(buffer);
                if (previousBlock == null) {
                    block.setBalanceList(BalanceList.fromByteBuffer(buffer));
                } else {
                    block.setBalanceList(Block.balanceListForNextBlock(previousBlock, block.getTransactions(),
                            block.getVerifierIdentifier()));
                }
                blocks.add(block);

                previousBlock = block;
            }
        } catch (Exception ignored) { }

        if (addBlocksToCache) {
            for (Block block : blocks) {
                BlockManagerMap.addBlock(block);
            }
        }

        return blocks;
    }

    public static boolean writeBlocksToFile(List<Block> blocks, File file) {

        boolean successful = false;

        int size = 2;  // number of blocks
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            size += block.getByteSize();
            if (i == 0) {
                size += block.getBalanceList().getByteSize();
            }
        }

        byte[] bytes = new byte[size];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.putShort((short) blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            buffer.put(block.getBytes());
            if (i == 0) {
                buffer.put(block.getBalanceList().getBytes());
            }
        }

        try {
            file.getParentFile().mkdirs();
            file.delete();
            Files.write(Paths.get(file.getAbsolutePath()), bytes);
            successful = true;
        } catch (Exception reportOnly) {
            System.err.println("error writing blocks to file: " + reportOnly.getMessage());
        }

        System.out.println("wrote blocks to file " + file.getAbsolutePath());

        return successful;
    }

    public static void freezeBlock(Block block) {

        // If the balance list is null, try to create it now.
        if (block.getBalanceList() == null) {
            Block previousBlock = null;
            if (block.getBlockHeight() > 0L) {
                previousBlock = BlockManager.frozenBlockForHeight(block.getBlockHeight() - 1);
            }
            if (previousBlock != null || block.getBlockHeight() == 0L) {
                block.setBalanceList(Block.balanceListForNextBlock(previousBlock, block.getTransactions(),
                        block.getVerifierIdentifier()));
            }
        }

        BalanceList balanceList = block.getBalanceList();
        CycleInformation cycleInformation = block.getCycleInformation();
        if (balanceList == null || cycleInformation == null) {
            System.err.println("unable to freeze block " + block.getBalanceList() + " because its balance list or " +
                    "cycle information is null");
        } else {
            synchronized (BlockManager.class) {
                try {
                    File file = fileForBlockHeight(block.getBlockHeight());
                    List<Block> blocksInFile = blocksInFile(file, true);
                    int expectedNumberOfBlocksInFile = (int) (block.getBlockHeight() % blocksPerFile);
                    if (blocksInFile.size() == expectedNumberOfBlocksInFile) {
                        setHighestBlockFrozen(block.getBlockHeight());
                        if (cycleInformation.isNewVerifier()) {
                            nextNewVerifierMinimumHeight.set(block.getBlockHeight() +
                                    cycleInformation.getCycleLength() + 2);
                        }

                        blocksInFile.add(block);
                        writeBlocksToFile(blocksInFile, file);
                        BlockManagerMap.addBlock(block);
                        BlockManagerMap.addBlock(block);
                    } else {
                        System.err.println("unable to write block " + block.getBlockHeight() + " : " +
                                blocksInFile.size() + " != " + expectedNumberOfBlocksInFile);
                        Throwable throwable = new Throwable();
                        StringBuilder stackTrace = new StringBuilder();
                        for (int i = 0; i < throwable.getStackTrace().length && i < 3; i++) {
                            stackTrace.append(throwable.getStackTrace()[i] + " -- ");
                        }
                        System.err.println(stackTrace.toString());
                    }

                } catch (Exception reportOnly) {
                    reportOnly.printStackTrace();
                    System.err.println("exception writing block to file " + reportOnly.getMessage());
                }
            }
        }
    }

    public static File fileForBlockHeight(long blockHeight, String extension) {

        // This format provides 158.5 years of blocks with nicely aligned names. After that, it will still work fine,
        // but the filenames will be wider.
        long fileIndex = blockHeight / blocksPerFile;
        long directoryIndex = blockHeight / blocksPerFile / filesPerDirectory;
        File directory = new File(blockRootDirectory, String.format("%03d", directoryIndex));
        return new File(directory, String.format("%06d.%s", fileIndex, extension));
    }

    private static File fileForBlockHeight(long blockHeight) {

        return fileForBlockHeight(blockHeight, "nyzoblock");
    }

    private static void loadBlockFromFile(long blockHeight) {

        List<Block> blocks = blocksInFile(fileForBlockHeight(blockHeight), true);
        System.out.println("loaded " + blocks.size() + " blocks for file " + fileForBlockHeight(blockHeight).getName());
    }

    private static void fetchBlockFromNetwork(long blockHeight) {


    }

    private static void initialize() {

        // This method only needs to load the locally stored blocks, and it can do so synchronously.

        System.out.println("looking for file " + fileForBlockHeight(0L).getAbsolutePath());
        if (fileForBlockHeight(0).exists()) {

            System.out.println("Genesis block file exists");

            // Load the Genesis block start timestamp.
            List<Block> blocksInGenesisFile = blocksInFile(fileForBlockHeight(0L), true);
            if (blocksInGenesisFile.size() > 0) {
                Block genesisBlock = blocksInGenesisFile.get(0);
                Block.genesisBlockStartTimestamp = genesisBlock.getStartTimestamp();
            }

            // Load the highest block available.
            long highestFileStartBlock = 0L;
            while (fileForBlockHeight(highestFileStartBlock + BlockManager.blocksPerFile).exists()) {
                highestFileStartBlock += BlockManager.blocksPerFile;
            }

            List<Block> blocks = blocksInFile(fileForBlockHeight(highestFileStartBlock), true);
            if (blocks.size() > 0) {
                setHighestBlockFrozen(blocks.get(blocks.size() - 1).getBlockHeight());
            }
        }
    }

    public static void setHighestBlockFrozen(long height) {

        if (height < highestBlockFrozen.get()) {
            System.out.println("Setting highest block frozen to a lesser value than is currently set.");
        }

        highestBlockFrozen.set(height);
    }

    public static long heightForTimestamp(long timestamp) {

        return (timestamp - Block.genesisBlockStartTimestamp) / Block.blockDuration;
    }

    public static long startTimestampForHeight(long blockHeight) {

        return Block.genesisBlockStartTimestamp + blockHeight * Block.blockDuration;
    }

    public static long endTimestampForHeight(long blockHeight) {

        return Block.genesisBlockStartTimestamp + (blockHeight + 1L) * Block.blockDuration;
    }

    public static long highestBlockOpenForProcessing() {

        // A block is considered open for processing 3 seconds after it completes, which is 8 seconds after it starts.
        return Block.genesisBlockStartTimestamp > 0 ?
                ((System.currentTimeMillis() - 8000L - Block.genesisBlockStartTimestamp) / Block.blockDuration) : -1;
    }

    public static void reset() {

        highestBlockFrozen.set(-1L);
    }

    public static boolean readyToProcess() {

        // TODO: wait until we have determined the highest block available
        return highestBlockFrozen() >= 0;
    }
}