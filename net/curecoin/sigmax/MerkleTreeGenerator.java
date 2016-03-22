package net.curecoin.sigmax;

/*
 * SigmaX 1.0.0b1 Source Code
 * Copyright (c) 2016 SigmaX Developers
 * Distributed under MIT License
 * Requires Apache Commons Library
 * Supports Java 1.7+
 */

import java.io.*;
import java.util.*;
import java.security.*;
import java.nio.file.*;
/**
 * This class is used to generate Merkle Trees from private keys. The top of the generated Merkle Tree is the SigmaX address of the tree.
 * The network supports Merkle Trees between 14 and 18 layers large. 
 * The bottom layer of the Merkle Tree is created by generating individual groups of Lamport Signature Private Keys, then hashing them to a Lamport
 * Signature Public Key.
 * Each Lamport Signature Private Key is used to sign exactly one transaction.
 * A Merkle Tree with 14 layers can sign 8192 transactions, and one with 18 layers can sign 131,072 transactions.
 * The Lamport Signature Private Key Parts are generated by seeding a SecureRandom object with a byte array representation of the private key. 
 * As they are trivial to produce, are relatively large, and can be produced on-demand for signatures, these Lamport Private Keys are generated 
 * when they are needed, rather than stored.
 * The higher layers of the Merkle Tree are easy to store, and take a long time to generate, and therefore are stored on the hard drive.
 * The entire tree could be deterministically reproduced for each signature, if desired. 
 * Rather than generating all of the Lamport Signatures from the same SecureRandom object, the SecureRandom object seeded with the private key
 * is used to generate all of the private seeds, one for each Lamport Private Key. This reduces the time to produce Lamport Private Keys
 * for signatures by a factor of nearly 2*SIGNATURE_BITS. 
 */
public class MerkleTreeGenerator
{
	public static final int SIGNATURE_BITS = 100; //Each Lamport Private Key will contain 2x this number of Private Lamport Key Parts. Not used, only for information.
    public static final int LAMPORT_PRIVATE_PART_SIZE = 20; //Maximum size of Lamport Private Part. At 1E12 tries per second, would take 22,337,120,292,586,187 years to brute-force one.  Not used, only for information.

	public static final String SOFTWARE_VERSION = "2.0.0a";

    private static org.apache.commons.codec.binary.Base32 base32 = new org.apache.commons.codec.binary.Base32();
    private static org.apache.commons.codec.binary.Base64 base64 = new org.apache.commons.codec.binary.Base64();
    private static MessageDigest md;
    static
    {
        try
        {
            SecureRandom.getInstance("SHA1PRNG"); //Test for SHA1PRNG being available. Should never fail.
        } catch (Exception e)
        {
            System.out.println("CRITICAL ERROR: NO SHA1PRNG SUPPORT! EXITING APPLICATION");
        }
        try
        {
            md = MessageDigest.getInstance("SHA-256"); //Initializes md for SHA256 functions to use
        } catch (Exception e)
        {
            System.out.println("CRITICAL ERROR: NO SHA-256 SUPPORT! EXITING APPLICATION");
            e.printStackTrace();
            System.exit(-1);
        }
        try //Checks for addresses folder, if it doesn't exist, it creates. If it fails (likely due to write permission issues), the application exits. 
        {
            File addressFolder = new File("addresses");
            if (!addressFolder.exists())
            {
                addressFolder.mkdir();
            }
        } catch (Exception e)
        {
            System.out.println("CRITICAL ERROR: UNABLE TO CREATE FOLDER FOR ADDRESS STORAGE! EXITING APPLICATION.");
            e.printStackTrace();
            System.exit(-1);
        }
    }
    
    private static final String ADDRESS_FIRST_LETTER = "S";
    
    public static void main(String[] args) //A messy test method, in here for convenience. Will be removed before final release.
    {
        //launch();
        Scanner scan = new Scanner(System.in);
        System.out.println("Generate address (1) normally or (2) from scratch file or (3) just generate scratch file?");
        String input = scan.nextLine();
        if (input.equals("2"))
        {
            System.out.println("Please enter the name of the scratch file...");
            String scratchFileName = scan.nextLine();
            System.out.println("Please enter the size of Merkle Tree you want...");
            int size = scan.nextInt();
            long currentTime = System.currentTimeMillis();
            String address = MerkleTreeGenerator.generateMerkleTreeFromScratchFile(scratchFileName, size);
            System.out.println("Took: " + (System.currentTimeMillis() - currentTime) + "ms");
            System.out.println("Address: " + address);
        }
        else if (input.equals("3"))
        {
            System.out.println("What is the private key?");
            String privateKey = scan.nextLine();
            System.out.println("What would you like to call the scratch file?");
            String scratchName = scan.nextLine();
            System.out.println("Please enter the size of Merkle Tree you want...");
            int size = scan.nextInt();
            System.out.println("How many threads would you like to run on?");
            int threads = scan.nextInt();
            long currentTime = System.currentTimeMillis();
            MerkleTreeGenerator.generateScratchFile(scratchName, privateKey, size, threads, 512);
            System.out.println("Took: " + (System.currentTimeMillis() - currentTime) + "ms");
        }
        else
        {
            System.out.println("What is the private key?");
            String privateKey = scan.nextLine();
            System.out.println("Please enter the size of Merkle Tree you want...");
            int size = scan.nextInt();
            System.out.println("How many threads would you like to run on?");
            int threads = scan.nextInt();
            long currentTime = System.currentTimeMillis();
            String address = MerkleTreeGenerator.generateMerkleTree(privateKey, size, threads, 512);
            System.out.println("Took: " + (System.currentTimeMillis() - currentTime) + "ms");
            System.out.println("Address: " + address);
        }
        scan.close();
    }

    /**
     * This method will produce a Merkle Tree for the given privateKey and number of layers.
     * All use of SecureRandom is seeded by the privateKey, so multiple calls to generateMerkleTree() with the same
     * parameters will yield the exact same Merkle Tree.
     * Produced Merkle Tree is saved to the addresses folder. 
     * Note that layers include the bottom hashed private key parts, as well as the top, which contains the address.
     * 
     * @param privateKey A String which holds the plaintext private key of an address, generally generated by SecureStringGenerator.
     * @param numLayers The number of layers to build the Merkle Tree out of. A Merkle Tree of n layers can sign 2^(n-1) transactions.
     * 
     * @return String the SigmaX address corresponding to the generated Merkle tree
     */
    public static String generateMerkleTree(String privateKey, int numLayers, int numThreads, int keysPerThread)
    {
        if (numThreads < 1)
        {
            numThreads = 1;
        }
        if (privateKey == null)
        {
            return null;
        }
        generateScratchFile("scratch", privateKey, numLayers, numThreads, keysPerThread);
        return generateMerkleTreeFromScratchFile("scratch", numLayers);
    }

    public static boolean generateScratchFile(String scratchFileName, String privateKey, int numLayers, int numThreads, int keysPerThread)
    {
        ArrayList<LamportGenThread> threads = new ArrayList<LamportGenThread>(); //ArrayList to hold worker threads
        for (int j = 0; j < numThreads; j++)
        {
            threads.add(new LamportGenThread()); //Initial setup, sanity check, and to make the normal thread removal loop not require a conditional.
        }
        try
        {
            //SecureRandom seeded by privateKey will be used to generate private seeds for all Merkle Trees
            SecureRandom generatePrivateSeeds = SecureRandom.getInstance("SHA1PRNG");
            generatePrivateSeeds.setSeed(privateKey.getBytes());
            //First layer will hold hashes of Lamport Private Key bases, which must be generated
            long lastPrint = System.currentTimeMillis();
            PrintWriter scratch = new PrintWriter(new File(scratchFileName));
            for (int i = 0; i < (int)Math.pow(2, (numLayers-1)); i++) //2^(numLayers-1) is how many Lamport Signatures need to be generated. Also max possible signatures.
            {
                System.out.println(i + "/" + Math.pow(2, (numLayers - 1)));
                double increaseInKeys = (numThreads * keysPerThread);
                double timeChange = System.currentTimeMillis() - lastPrint;
                lastPrint = System.currentTimeMillis();
                double keysPerSecond = increaseInKeys/(timeChange / 1000);
                System.out.println("Rate: " + keysPerSecond + " keys per second.");
                //generatePrivateSeeds.nextBytes(privateSeed); //privateSeed now holds the ith private seed for Lamport Signature Generation
                for (int j = 0; j < numThreads; j++) //Clear thread pool
                {
                    threads.remove(0);
                }
                for (int j = 0; j < numThreads; j++) //Create new threads for generating public keys
                {
                    threads.add(new LamportGenThread());
                }
                for (int j = 0; j < numThreads; j++) //Fill in seeds for all generation threads
                {
                    byte[][] seeds = new byte[keysPerThread][100]; //100 bits used as the private key
                    for (int q = 0; q < keysPerThread; q++)
                    {
                        generatePrivateSeeds.nextBytes(seeds[q]);
                    }
                    threads.get(j).setData(seeds, keysPerThread);
                }
                for (int j = 0; j < numThreads; j++) //Start worker threads
                {
                    threads.get(j).start();
                }
                int offset = 0; //Handle increments to i to keep on track with multithreaded progress. Originally, this for loop ran once for every public Lamport keyset.
                for (int j = 0; j < numThreads; j++)
                {
                    threads.get(j).join(); //Wait for thread to finish
                    String[] keys = threads.get(j).getPublicKeys(); //Retrieve public keys in order
                    for (int q = 0; q < keysPerThread; q++)
                    {
                        if ((int)Math.pow(2, (numLayers-1)) > (i + offset + q)) //not beyond the base size required for the tree
                        {
                            scratch.println(keys[q]);
                        }
                    }
                    offset += keysPerThread; //Add keysPerThread each time through the loop to keep offset at the proper location in the base tree
                }
                i += (numThreads * keysPerThread) - 1; //Subtract one as for loop adds 1 every loop
            }
            scratch.close(); //Scratch file will be read in later, release lock
            return true;
        } catch (Exception e)
        {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * This method will produce a Merkle Tree for the given privateKey and number of layers.
     * All use of SecureRandom is seeded by the privateKey, so multiple calls to generateMerkleTree() with the same
     * parameters will yield the exact same Merkle Tree.
     * Produced Merkle Tree is saved to the addresses folder. 
     * @param privateKey A String which holds the plaintext private key of an address, generally generated by SecureStringGenerator.
     * @param numLayers The number of layers to build the Merkle Tree out of. A Merkle Tree of n layers can sign 2^(n-1) transactions.
     * Note that layers include the bottom hashed private key parts, as well as the top, which contains the address.
     * @return String The SigmaX address generated from the merkle tree
     */
    public static String generateMerkleTreeFromScratchFile(String scratchFileName, int numLayers)
    {
        try
        {
            String tempDir = new Random().nextInt(10000000) + ""; //Name of temporary directory to hold progress files. Not deleted on failure for manual recovery purposes.
            File tempDirFile = new File(tempDir);
            tempDirFile.mkdir();

            File layer0File = new File(scratchFileName);
            File layer0Destination = new File(tempDir + "/layer0.lyr");
            Files.move(layer0File.toPath(), layer0Destination.toPath());
            for (int i = 1; i < numLayers - 1; i++)
            {
                PrintWriter out = new PrintWriter(new File(tempDir + "/layer" + i + ".lyr"));
                Scanner scanPreviousLayer = new Scanner(new File(tempDir + "/layer" + (i - 1) + ".lyr"));
                int readCount = 0;
                long previousTime = System.currentTimeMillis();
                while (scanPreviousLayer.hasNextLine())
                {
                    readCount++;
                    if (readCount % 100000 == 0)
                    {
                        System.out.println((100000.0)/(((double)System.currentTimeMillis() - (double)previousTime)/1000.0) + " entries per second.");
                        previousTime = System.currentTimeMillis();
                    }
                    out.println(SHA256(scanPreviousLayer.nextLine() + scanPreviousLayer.nextLine()));
                }
                scanPreviousLayer.close();
                out.close();
            }
            File preAddressFile = new File(tempDir + "/layer" + (numLayers - 2) + ".lyr");
            Scanner scanForAddress = new Scanner(preAddressFile);
            String one = scanForAddress.nextLine();
            String two = scanForAddress.nextLine();
            scanForAddress.close();
            PrintWriter outFinalLayer = new PrintWriter(new File(tempDir + "/layer" + (numLayers - 1) + ".lyr"));
            outFinalLayer.println(SHA256(one + two));
            outFinalLayer.close();
            String preAddress = SHA256ReturnBase32(one + two);
            String address; //C# + pre-address + first 4 characters of hash of pre-address (sanity check, protect against mistypes)
            if (numLayers == 14)
            {
                address = ADDRESS_FIRST_LETTER + "1" + preAddress + SHA256ReturnBase32(ADDRESS_FIRST_LETTER + "1" + preAddress).substring(0, 4); //14-layer is an S1 address
            }
            else if (numLayers == 15)
            {
                address = ADDRESS_FIRST_LETTER + "2" + preAddress + SHA256ReturnBase32(ADDRESS_FIRST_LETTER + "2" + preAddress).substring(0, 4); //15-layer is an S2 address
            }
            else if (numLayers == 16)
            {
                address = ADDRESS_FIRST_LETTER + "3" + preAddress + SHA256ReturnBase32(ADDRESS_FIRST_LETTER + "3" + preAddress).substring(0, 4); //16-layer is an S3 address
            }
            else if (numLayers == 17)
            {
                address = ADDRESS_FIRST_LETTER + "4" + preAddress + SHA256ReturnBase32(ADDRESS_FIRST_LETTER + "4" + preAddress).substring(0, 4); //17-layer is an S4 address
            }
            else if (numLayers == 18)
            {
                address = ADDRESS_FIRST_LETTER + "C" + preAddress + SHA256ReturnBase32(ADDRESS_FIRST_LETTER + "5" + preAddress).substring(0, 4); //18-layer is an S5 address
            }
            else //Not a SigmaX address!
            {
                address = "F1" + preAddress + SHA256ReturnBase32("F1" + preAddress).substring(0, 4); //Non-supported layer!
            }
            File addressFile = new File("addresses/" + address);
            if (!addressFile.exists())
            {
                addressFile.mkdir();
                for (int i = 0; i < numLayers; i++)
                {
                    File layerFile = new File(tempDir + "/layer" + i + ".lyr");
                    File layerDestination = new File("addresses/" + address + "/layer" + i + ".lyr");
                    Files.move(layerFile.toPath(), layerDestination.toPath());
                }
                tempDirFile.delete();
                PrintWriter infoFileWriter = new PrintWriter(new File("addresses/" + address + "/info.dta"));
                infoFileWriter.println("address: " + address);
                infoFileWriter.println("layers: " + numLayers);
                infoFileWriter.println("software_version: 2.0.0a");
                infoFileWriter.close();
            }
            return address;
        } catch (Exception e)
        {
            e.printStackTrace();
        }
        return null;
    }
    
    /**
     * This SHA256 function returns a 16-character, base64 String. The String is shortened to reduce space on the blockchain, and is sufficiently long for security purposes.
     * @param toHash The String to hash using SHA256
     * @return String The 16-character base64 String resulting from hashing toHash and truncating
     */
    @SuppressWarnings("unused")
	private static String SHA256Short(String toHash) //Each hash is shortened to 16 characters based on a 64-character charset. 64^16=79,228,162,514,264,337,593,543,950,336 (Aka more than enough for Lamport)
    {
        try
        {
            return base64.encodeAsString(md.digest(toHash.getBytes("UTF-8"))).substring(0, 16);
        } catch (Exception e)
        {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * This SHA256 function returns a base64 String repesenting the full SHA256 hash of the String toHash
     * The full-length SHA256 hashes are used for the non-Lamport and non-Address levels of the Merkle Tree
     * @param toHash The String to hash using SHA256
     * @return String the base64 String representing the entire SHA256 hash of toHash
     */
    private static String SHA256(String toHash)
    {
        try
        {
            return base64.encodeAsString(md.digest(toHash.getBytes("UTF-8")));
        } catch (Exception e)
        {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Used for the generation of an address. Base32 is more practical for real-world addresses, due to a more convenient ASCII charset.
     * Shortened to 32 characters, as that provides 32^32=1,461,501,637,330,902,918,203,684,832,716,283,019,655,932,542,976 possible addresses.
     * @param toHash The String to hash using SHA256
     * @return String the base32-encoded String representing the entire SHA256 hash of toHash
     */
    private static String SHA256ReturnBase32(String toHash) 
    {
        try
        {
            return base32.encodeAsString(md.digest(toHash.getBytes("UTF-8"))).substring(0, 32);
        } catch (Exception e)
        {
            e.printStackTrace();
        }
        return null;
    }
}
