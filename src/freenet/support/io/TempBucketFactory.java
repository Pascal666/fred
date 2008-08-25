/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import freenet.crypt.RandomSource;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.SizeUtil;
import freenet.support.TimeUtil;
import java.io.IOException;

import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Temporary Bucket Factory
 * 
 * Buckets created by this factory can be either:
 *	- ArrayBuckets
 * OR
 *	- FileBuckets
 * 
 * ArrayBuckets are used if and only if:
 *	1) there is enough room remaining on the pool (@see maxRamUsed and @see bytesInUse)
 *	2) the initial size is smaller than (@maxRAMBucketSize)
 * 
 * Depending on how they are used they might switch from one type to another transparently.
 * 
 * Currently they are two factors considered for a migration:
 *	- if they are long-lived or not (@see RAMBUCKET_MAX_AGE)
 *	- if their size is over RAMBUCKET_CONVERSION_FACTOR*maxRAMBucketSize
 */
public class TempBucketFactory implements BucketFactory {
	public final static long defaultIncrement = 4096;
	public final static float DEFAULT_FACTOR = 1.25F;
	
	private final FilenameGenerator filenameGenerator;
	private long bytesInUse = 0;
	private final RandomSource strongPRNG;
	private final Random weakPRNG;
	private final Executor executor;
	private volatile boolean logMINOR;
	private volatile boolean reallyEncrypt;
	
	/** How big can the defaultSize be for us to consider using RAMBuckets? */
	private long maxRAMBucketSize;
	/** How much memory do we dedicate to the RAMBucketPool? (in bytes) */
	private long maxRamUsed;
	
	/** How old is a long-lived RAMBucket? */
	private final int RAMBUCKET_MAX_AGE = 5*60*1000; // 5mins
	/** How many times the maxRAMBucketSize can a RAMBucket be before it gets migrated? */
	private final int RAMBUCKET_CONVERSION_FACTOR = 4;
	
	public class TempBucket implements Bucket {
		private Bucket currentBucket;
		private long currentSize;
		private OutputStream os = null;
		private short osIndex;
		private short shouldResetIS = 0;
		private short isIndex;
		public final Object sync = new Object();
		public final long creationTime;
		
		public TempBucket(long now, Bucket cur) {
			if(cur == null)
				throw new NullPointerException();
			this.currentBucket = cur;
			this.creationTime = now;
			this.osIndex = 0;
			this.isIndex = 0;
		}
		
		/** A blocking method to force-migrate from a RAMBucket to a FileBucket */
		private final void migrateToFileBucket() throws IOException {
			Bucket toMigrate = null;
			synchronized(sync) {
				if(!isRAMBucket())
					// Nothing to migrate! We don't want to switch back to ram, do we?					
					return;

				toMigrate = currentBucket;
				Bucket tempFB = _makeFileBucket();
				if(os != null) {
					os.flush();
					os.close();
				}
				os = tempFB.getOutputStream();
				BucketTools.copyTo(tempFB, os, currentSize);
				if(toMigrate.isReadOnly())
					tempFB.setReadOnly();
				currentBucket = tempFB;
				// We need streams to be reset to point to the new bucket
				shouldResetIS = isIndex;
			}
			if(logMINOR)
				Logger.minor(this, "We have migrated "+toMigrate.hashCode());
			
			// We can free it on-thread as it's a rambucket
			toMigrate.free();
			// Might have changed already so we can't rely on currentSize!
			_hasFreed(toMigrate.size());
		}
		
		public final boolean isRAMBucket() {
			synchronized(sync) {
				return (currentBucket instanceof ArrayBucket);
			}
		}

		public OutputStream getOutputStream() throws IOException {
			synchronized(sync) {
				if(os != null)
					throw new IOException("Only one OutputStream per bucket!");
				return new TempBucketOutputStream(++osIndex);
			}
		}

		private class TempBucketOutputStream extends OutputStream {
			private final short idx;
			
			TempBucketOutputStream(short idx) throws IOException {
				this.idx = idx;
				os = currentBucket.getOutputStream();
			}
			
			private void _maybeMigrateRamBucket(long futureSize) throws IOException {
				if(isRAMBucket()) {
					boolean shouldMigrate = false;
					boolean isOversized = false;
					
					if(futureSize > maxRAMBucketSize * RAMBUCKET_CONVERSION_FACTOR) {
						isOversized = true;
						shouldMigrate = true;
					} else if ((futureSize - currentSize) + bytesInUse > maxRamUsed)
						shouldMigrate = true;
					
					if(shouldMigrate) {
						if(logMINOR) {
							if(isOversized)
								Logger.minor(this, "The bucket is over "+SizeUtil.formatSize(maxRAMBucketSize*RAMBUCKET_CONVERSION_FACTOR)+": we will force-migrate it to disk.");
							else
								Logger.minor(this, "The bucketpool is full: force-migrate before we go over the limit");
						}
						migrateToFileBucket();
					}
				}
			}
			
			@Override
			public final void write(int b) throws IOException {
				synchronized(sync) {
					long futureSize = currentSize + 1;
					_maybeMigrateRamBucket(futureSize);
					os.write(b);
					currentSize = futureSize;
					if(isRAMBucket()) // We need to re-check because it might have changed!
						_hasTaken(1);
				}
			}
			
			@Override
			public final void write(byte b[], int off, int len) throws IOException {
				synchronized(sync) {
					long futureSize = currentSize + len;
					_maybeMigrateRamBucket(futureSize);
					os.write(b, off, len);
					currentSize = futureSize;
					if(isRAMBucket()) // We need to re-check because it might have changed!
						_hasTaken(len);
				}
			}
			
			@Override
			public final void flush() throws IOException {
				synchronized(sync) {
					_maybeMigrateRamBucket(currentSize);
					os.flush();
				}
			}
			
			@Override
			public final void close() throws IOException {
				synchronized(sync) {
					_maybeMigrateRamBucket(currentSize);
					if(os != null) {
						os.flush();
						os.close();
					}
				}
			}
		}

		public synchronized InputStream getInputStream() throws IOException {
			isIndex++;
			return new TempBucketInputStream(osIndex);
		}
		
		private class TempBucketInputStream extends InputStream {
			private InputStream currentIS;
			private long index = 0;
			private final short idx;
			
			TempBucketInputStream(short idx) throws IOException {
				this.idx = idx;
				this.currentIS = currentBucket.getInputStream();
			}
			
			private void _maybeResetInputStream() throws IOException {
				if(idx != osIndex)
					throw new IOException("Should use the new InputStream!");
				
				if(shouldResetIS > 0) {
					Closer.close(currentIS);
					currentIS = currentBucket.getInputStream();
					currentIS.skip(index);
					shouldResetIS--;
				}
			}
			
			@Override
			public final int read() throws IOException {
				synchronized(sync) {
					_maybeResetInputStream();
					int toReturn = currentIS.read();
					if(toReturn > -1)
						index++;
					return toReturn;
				}
			}
			
			@Override
			public int read(byte b[]) throws IOException {
				return read(b, 0, b.length);
			}
			
			@Override
			public int read(byte b[], int off, int len) throws IOException {
				synchronized(sync) {
					_maybeResetInputStream();
					int toReturn = currentIS.read(b, off, len);
					if(toReturn > -1)
						index += len;
					return toReturn;
				}
			}
			
			@Override
			public long skip(long n) throws IOException {
				synchronized(sync) {
					_maybeResetInputStream();
					long skipped = currentIS.skip(n);
					index += skipped;
					return skipped;
				}
			}
			
			@Override
			public boolean markSupported() {
				return false;
			}
			
			@Override
			public final void close() throws IOException {
				synchronized(sync) {
					_maybeResetInputStream();
					Closer.close(currentIS);
					isIndex--;
				}
			}
		}

		public String getName() {
			synchronized(sync) {
				return currentBucket.getName();
			}
		}

		public long size() {
			synchronized(sync) {
				return currentSize;
			}
		}

		public boolean isReadOnly() {
			synchronized(sync) {
				return currentBucket.isReadOnly();
			}
		}

		public void setReadOnly() {
			synchronized(sync) {
				currentBucket.setReadOnly();
			}
		}

		public void free() {
			synchronized(sync) {
				currentBucket.free();
				if(isRAMBucket())
					_hasFreed(currentSize);
			}
		}
	}
	
	// Storage accounting disabled by default.
	public TempBucketFactory(Executor executor, FilenameGenerator filenameGenerator, long maxBucketSizeKeptInRam, long maxRamUsed, RandomSource strongPRNG, Random weakPRNG, boolean reallyEncrypt) {
		this.filenameGenerator = filenameGenerator;
		this.maxRamUsed = maxRamUsed;
		this.maxRAMBucketSize = maxBucketSizeKeptInRam;
		this.strongPRNG = strongPRNG;
		this.weakPRNG = weakPRNG;
		this.reallyEncrypt = reallyEncrypt;
		this.executor = executor;
		this.logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}

	public Bucket makeBucket(long size) throws IOException {
		return makeBucket(size, DEFAULT_FACTOR, defaultIncrement);
	}

	public Bucket makeBucket(long size, float factor) throws IOException {
		return makeBucket(size, factor, defaultIncrement);
	}
	
	private synchronized void _hasTaken(long size) {
		bytesInUse += size;
	}
	
	private synchronized void _hasFreed(long size) {
		bytesInUse -= size;
	}
	
	public synchronized long getRamUsed() {
		return bytesInUse;
	}
	
	public synchronized void setMaxRamUsed(long size) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		maxRamUsed = size;
	}
	
	public synchronized long getMaxRamUsed() {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		return maxRamUsed;
	}
	
	public synchronized void setMaxRAMBucketSize(long size) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		maxRAMBucketSize = size;
	}
	
	public synchronized long getMaxRAMBucketSize() {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		return maxRAMBucketSize;
	}
	
	public void setEncryption(boolean value) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		reallyEncrypt = value;
	}
	
	public boolean isEncrypting() {
		return reallyEncrypt;
	}

	/**
	 * Create a temp bucket
	 * 
	 * @param size
	 *            Default size
	 * @param factor
	 *            Factor to increase size by when need more space
	 * @return A temporary Bucket
	 * @exception IOException
	 *                If it is not possible to create a temp bucket due to an
	 *                I/O error
	 */
	public TempBucket makeBucket(long size, float factor, long increment) throws IOException {
		Bucket realBucket = null;
		boolean useRAMBucket = false;
		long now = System.currentTimeMillis();
		
		// We need to clean the queue in order to have "space" to host new buckets
		cleanBucketQueue(now);
		synchronized(this) {
			if((size > 0) && (size <= maxRAMBucketSize) && (bytesInUse <= maxRamUsed)) {
				useRAMBucket = true;
			}
		}
		
		// Do we want a RAMBucket or a FileBucket?
		realBucket = (useRAMBucket ? new ArrayBucket() : _makeFileBucket());
		
		TempBucket toReturn = new TempBucket(now, realBucket);
		if(useRAMBucket) { // No need to consider them for migration if they can't be migrated
			synchronized(ramBucketQueue) {
				ramBucketQueue.add(toReturn);
			}
		}
		return toReturn;
}
	
	/** Migrate all long-lived buckets from the queue */
	private void cleanBucketQueue(long now) {
		boolean shouldContinue = true;
		// create a new list to avoid race-conditions
		final Queue<TempBucket> toMigrate = new LinkedList<TempBucket>();
		do {
			synchronized(ramBucketQueue) {
				final TempBucket tmpBucket = ramBucketQueue.peek();
				if((tmpBucket == null) || (tmpBucket.creationTime + RAMBUCKET_MAX_AGE > now))
					shouldContinue = false;
				else {
					if(logMINOR)
						Logger.minor(this, "The bucket is "+TimeUtil.formatTime(now - tmpBucket.creationTime)+" old: we will force-migrate it to disk.");
					ramBucketQueue.remove(tmpBucket);
					toMigrate.add(tmpBucket);
				}
			}
		} while(shouldContinue);

		if(toMigrate.size() > 0) {
			executor.execute(new Runnable() {

				public void run() {
					if(logMINOR)
						Logger.minor(this, "We are going to migrate " + toMigrate.size() + " RAMBuckets");
					for(TempBucket tmpBucket : toMigrate) {
						try {
							tmpBucket.migrateToFileBucket();
						} catch(IOException e) {
							Logger.error(tmpBucket, "An IOE occured while migrating long-lived buckets:" + e.getMessage(), e);
						}
					}
				}
			}, "RAMBucket migrator ("+now+')');
		}
	}
	
	private final Queue<TempBucket> ramBucketQueue = new LinkedBlockingQueue<TempBucket>();
	
	private Bucket _makeFileBucket() {
		Bucket fileBucket = new TempFileBucket(filenameGenerator.makeRandomFilename(), filenameGenerator);
		// Do we want it to be encrypted?
		return (reallyEncrypt ? new PaddedEphemerallyEncryptedBucket(fileBucket, 1024, strongPRNG, weakPRNG) : fileBucket);
	}
}
