/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.HashSet;

import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

public class AnnounceSender implements Runnable, ByteCounter {

    // Constants
    static final int ACCEPTED_TIMEOUT = 5000;
    static final int ANNOUNCE_TIMEOUT = 240000; // longer than a regular request as have to transfer noderefs hop by hop etc
    static final int END_TIMEOUT = 30000; // After received the completion message, wait 30 seconds for any late reordered replies
	
	private final PeerNode source;
	private final long uid;
	private final OpennetManager om;
	private final Node node;
	private Message msg;
	private byte[] noderefBuf;
	private int noderefLength;
	private short htl;
	private double nearestLoc;
	private double target;
	private static boolean logMINOR;
	private final AnnouncementCallback cb;
	private final PeerNode onlyNode;
	
	public AnnounceSender(Message m, long uid, PeerNode source, OpennetManager om, Node node) {
		this.source = source;
		this.uid = uid;
		this.msg = m;
		this.om = om;
		this.node = node;
		this.onlyNode = null;
		htl = (short) Math.min(m.getShort(DMT.HTL), node.maxHTL());
		nearestLoc = m.getDouble(DMT.NEAREST_LOCATION);
		target = m.getDouble(DMT.TARGET_LOCATION); // FIXME validate
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		cb = null;
	}

	public AnnounceSender(double target, OpennetManager om, Node node, AnnouncementCallback cb, PeerNode onlyNode) {
		source = null;
		this.uid = node.random.nextLong();
		msg = null;
		this.om = om;
		this.node = node;
		this.htl = node.maxHTL();
		this.target = target;
		this.cb = cb;
		this.onlyNode = onlyNode;
		noderefBuf = om.crypto.myCompressedFullRef();
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	public void run() {
		try {
			realRun();
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t+" announcing "+uid+" from "+source, t);
		} finally {
			if(source != null) {
				source.completedAnnounce(uid);
			}
			node.completed(uid);
			if(cb != null)
				cb.completed();
		}
	}

	private void realRun() {
		boolean hasForwarded = false;
		if(source != null) {
			try {
				source.sendAsync(DMT.createFNPAccepted(uid), null, 0, null);
			} catch (NotConnectedException e) {
				return;
			}
			if(!transferNoderef()) return;
		}
		
        double myLoc = node.lm.getLocation();
        if(Location.distance(target, myLoc) < Location.distance(target, nearestLoc)) {
            nearestLoc = myLoc;
            htl = node.maxHTL();
        } else {
        	if(source != null)
        		htl = node.decrementHTL(source, htl);
        }
        
		// Now route it.
		
        HashSet nodesRoutedTo = new HashSet();
        HashSet nodesNotIgnored = new HashSet();
        while(true) {
            if(logMINOR) Logger.minor(this, "htl="+htl);
            if(htl == 0) {
            	// No more nodes.
            	complete();
            	return;
            }
            
        	PeerNode next;
            if(onlyNode == null) {
            	// Route it
            	next = node.peers.closerPeer(source, nodesRoutedTo, nodesNotIgnored, target, true, node.isAdvancedModeEnabled(), -1, null);
            } else {
            	next = onlyNode;
            	if(nodesRoutedTo.contains(onlyNode)) {
            		rnf(onlyNode);
            		return;
            	}
            }
            
            if(next == null) {
                // Backtrack
            	rnf(next);
                return;
            }
            if(logMINOR) Logger.minor(this, "Routing request to "+next);
            nodesRoutedTo.add(next);
            
            if(hasForwarded)
            	htl = node.decrementHTL(source, htl);
            
            long xferUID = sendTo(next);
            if(xferUID == -1) continue;
            
            hasForwarded = true;
            
            Message msg = null;
            
            while(true) {
            	
                /**
                 * What are we waiting for?
                 * FNPAccepted - continue
                 * FNPRejectedLoop - go to another node
                 * FNPRejectedOverload - go to another node
                 */
                
                MessageFilter mfAccepted = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPAccepted);
                MessageFilter mfRejectedLoop = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPRejectedLoop);
                MessageFilter mfRejectedOverload = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPRejectedOverload);
                MessageFilter mfOpennetDisabled = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPOpennetDisabled);
                
                // mfRejectedOverload must be the last thing in the or
                // So its or pointer remains null
                // Otherwise we need to recreate it below
                MessageFilter mf = mfAccepted.or(mfRejectedLoop.or(mfRejectedOverload.or(mfOpennetDisabled)));
                
                try {
                    msg = node.usm.waitFor(mf, this);
                    if(logMINOR) Logger.minor(this, "first part got "+msg);
                } catch (DisconnectedException e) {
                    Logger.normal(this, "Disconnected from "+next+" while waiting for Accepted on "+uid);
                    break;
                }
                
            	if(msg == null) {
            		if(logMINOR) Logger.minor(this, "Timeout waiting for Accepted");
            		// Try next node
            		msg = null;
            		break;
            	}
            	
            	if(msg.getSpec() == DMT.FNPRejectedLoop) {
            		if(logMINOR) Logger.minor(this, "Rejected loop");
            		// Find another node to route to
            		msg = null;
            		break;
            	}
            	
            	if(msg.getSpec() == DMT.FNPRejectedOverload) {
            		if(logMINOR) Logger.minor(this, "Rejected: overload");
					// Give up on this one, try another
            		msg = null;
					break;
            	}
            	
            	if(msg.getSpec() == DMT.FNPOpennetDisabled) {
            		if(logMINOR) Logger.minor(this, "Opennet disabled");
            		msg = null;
            		break;
            	}
            	
            	if(msg.getSpec() != DMT.FNPAccepted) {
            		Logger.error(this, "Unrecognized message: "+msg);
            		continue;
            	}
            	
            	break;
            }
            
            if((msg == null) || (msg.getSpec() != DMT.FNPAccepted)) {
            	// Try another node
            	continue;
            }

            if(logMINOR) Logger.minor(this, "Got Accepted");
            
            // Send the rest
            
            try {
				sendRest(next, xferUID);
			} catch (NotConnectedException e1) {
				if(logMINOR)
					Logger.minor(this, "Not connected while sending noderef on "+next);
				continue;
			}
            
            // Otherwise, must be Accepted
            
            // So wait...
            
            while(true) {
            	
            	MessageFilter mfAnnounceCompleted = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ANNOUNCE_TIMEOUT).setType(DMT.FNPOpennetAnnounceCompleted);
            	MessageFilter mfRouteNotFound = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ANNOUNCE_TIMEOUT).setType(DMT.FNPRouteNotFound);
            	MessageFilter mfRejectedOverload = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ANNOUNCE_TIMEOUT).setType(DMT.FNPRejectedOverload);
            	MessageFilter mfAnnounceReply = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ANNOUNCE_TIMEOUT).setType(DMT.FNPOpennetAnnounceReply);
                MessageFilter mfOpennetDisabled = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ANNOUNCE_TIMEOUT).setType(DMT.FNPOpennetDisabled);
                MessageFilter mfNotWanted = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ANNOUNCE_TIMEOUT).setType(DMT.FNPOpennetAnnounceNodeNotWanted);
                MessageFilter mfOpennetNoderefRejected = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPOpennetNoderefRejected);
            	MessageFilter mf = mfAnnounceCompleted.or(mfRouteNotFound.or(mfRejectedOverload.or(mfAnnounceReply.or(mfOpennetDisabled.or(mfNotWanted.or(mfOpennetNoderefRejected))))));
            	
            	try {
            		msg = node.usm.waitFor(mf, this);
            	} catch (DisconnectedException e) {
            		Logger.normal(this, "Disconnected from "+next+" while waiting for announcement");
            		break;
            	}
            	
            	if(logMINOR) Logger.minor(this, "second part got "+msg);
            	
            	if(msg == null) {
            		// Fatal timeout, must be terminal (IS_LOCAL==true)
            		timedOut(next);
            		return;
            	}
            	
            	if(msg.getSpec() == DMT.FNPOpennetNoderefRejected) {
            		int reason = msg.getInt(DMT.REJECT_CODE);
            		Logger.normal(this, "Announce rejected by "+next+" : "+DMT.getOpennetRejectedCode(reason));
            		msg = null;
            		break;
            	}
            	
            	if(msg.getSpec() == DMT.FNPOpennetAnnounceCompleted) {
            		// Send the completion on immediately. We don't want to accumulate 30 seconds per hop!
            		complete();
            		mfAnnounceReply.setTimeout(END_TIMEOUT).setTimeoutRelativeToCreation(true);
            		mfNotWanted.setTimeout(END_TIMEOUT).setTimeoutRelativeToCreation(true);
            		mfAnnounceReply.clearOr();
            		mfNotWanted.clearOr();
            		mf = mfAnnounceReply.or(mfNotWanted);
            		while(true)  {
                    	try {
                    		msg = node.usm.waitFor(mf, this);
                    	} catch (DisconnectedException e) {
                    		return;
                    	}
            			if(msg == null) return;
            			if(msg.getSpec() == DMT.FNPOpennetAnnounceReply) {
            				validateForwardReply(msg, next);
            				continue;
            			}
            			if(msg.getSpec() == DMT.FNPOpennetAnnounceNodeNotWanted) {
                    		if(cb != null)
                    			cb.nodeNotWanted();
                    		if(source != null) {
        						try {
        							sendNotWanted();
        						} catch (NotConnectedException e) {
        							Logger.error(this, "Lost connection to source");
        							return;
        						}
                    		}
            				continue;
            			}
            		}
            	}
            	
            	if(msg.getSpec() == DMT.FNPRouteNotFound) {
            		// Backtrack within available hops
            		short newHtl = msg.getShort(DMT.HTL);
            		if(newHtl < htl) htl = newHtl;
            		break;
            	}

            	if(msg.getSpec() == DMT.FNPRejectedOverload) {
					// Give up on this one, try another
					break;
            	}
            	
            	if(msg.getSpec() == DMT.FNPOpennetDisabled) {
            		Logger.minor(this, "Opennet disabled");
            		msg = null;
            		break;
            	}
            	
            	if(msg.getSpec() == DMT.FNPOpennetAnnounceReply) {
            		validateForwardReply(msg, next);
            		continue; // There may be more
            	}
            	
            	if(msg.getSpec() == DMT.FNPOpennetAnnounceNodeNotWanted) {
            		if(cb != null)
            			cb.nodeNotWanted();
            		if(source != null) {
						try {
							sendNotWanted();
						} catch (NotConnectedException e) {
							Logger.error(this, "Lost connection to source");
							return;
						}
            		}
            		continue; // This message is propagated, they will send a Completed or RNF
            	}
            	
            	Logger.error(this, "Unexpected message: "+msg);
            }
        }
	}

	/**
	 * Validate a reply, and relay it back to the source.
	 * @param msg2 The AnnouncementReply message.
	 * @return True unless we lost the connection to our request source.
	 */
	private boolean validateForwardReply(Message msg, PeerNode next) {
		long xferUID = msg.getLong(DMT.TRANSFER_UID);
		int noderefLength = msg.getInt(DMT.NODEREF_LENGTH);
		int paddedLength = msg.getInt(DMT.PADDED_LENGTH);
		byte[] noderefBuf = om.innerWaitForOpennetNoderef(xferUID, paddedLength, noderefLength, next, false, uid, true, this);
		if(noderefBuf == null) {
			return true; // Don't relay
		}
		SimpleFieldSet fs = om.validateNoderef(noderefBuf, 0, noderefLength, next, false);
		if(fs == null) {
			if(cb != null) cb.bogusNoderef("invalid noderef");
			return true; // Don't relay
		}
		if(source != null) {
			// Now relay it
			try {
				om.sendAnnouncementReply(uid, source, noderefBuf, this);
			} catch (NotConnectedException e) {
				// Hmmm...!
				return false;
			}
		} else {
			// Add it
			try {
				OpennetPeerNode pn = node.addNewOpennetNode(fs);
				if(pn != null)
					cb.addedNode(pn);
				else
					cb.nodeNotAdded();
			} catch (FSParseException e) {
				Logger.normal(this, "Failed to parse reply: "+e, e);
				if(cb != null) cb.bogusNoderef("parse failed: "+e);
			} catch (PeerParseException e) {
				Logger.normal(this, "Failed to parse reply: "+e, e);
				if(cb != null) cb.bogusNoderef("parse failed: "+e);
			} catch (ReferenceSignatureVerificationException e) {
				Logger.normal(this, "Failed to parse reply: "+e, e);
				if(cb != null) cb.bogusNoderef("parse failed: "+e);
			}
		}
		return true;
	}

	/**
	 * Send an AnnouncementRequest.
	 * @param next The node to send the announcement to.
	 * @return True if the announcement was successfully sent.
	 */
	private long sendTo(PeerNode next) {
		try {
			return om.startSendAnnouncementRequest(uid, next, noderefBuf, this, target, htl, nearestLoc);
		} catch (NotConnectedException e) {
			if(logMINOR) Logger.minor(this, "Disconnected");
			return -1;
		}
	}

	/**
	 * Send an AnnouncementRequest.
	 * @param next The node to send the announcement to.
	 * @return True if the announcement was successfully sent.
	 * @throws NotConnectedException 
	 */
	private void sendRest(PeerNode next, long xferUID) throws NotConnectedException {
		om.finishSentAnnouncementRequest(next, noderefBuf, this, xferUID);
	}

	private void timedOut(PeerNode next) {
		Message msg = DMT.createFNPRejectedOverload(uid, true);
		if(source != null) {
			try {
				source.sendAsync(msg, null, 0, this);
			} catch (NotConnectedException e) {
				// Ok
			}
		}
		if(cb != null) cb.nodeFailed(next, "timed out");
	}

	private void rnf(PeerNode next) {
		Message msg = DMT.createFNPRouteNotFound(uid, htl);
		if(source != null) {
			try {
				source.sendAsync(msg, null, 0, this);
			} catch (NotConnectedException e) {
				// Ok
			}
		}
		if(cb != null) {
			if(next != null) cb.nodeFailed(next, "route not found");
			else cb.noMoreNodes();
		}
	}

	private void complete() {
		Message msg = DMT.createFNPOpennetAnnounceCompleted(uid);
		if(source != null) {
			try {
				source.sendAsync(msg, null, 0, this);
			} catch (NotConnectedException e) {
				// Oh well.
			}
		}
	}

	/**
	 * @return True unless the noderef is bogus.
	 */
	private boolean transferNoderef() {
		long xferUID = msg.getLong(DMT.TRANSFER_UID);
		noderefLength = msg.getInt(DMT.NODEREF_LENGTH);
		int paddedLength = msg.getInt(DMT.PADDED_LENGTH);
		noderefBuf = om.innerWaitForOpennetNoderef(xferUID, paddedLength, noderefLength, source, false, uid, true, this);
		if(noderefBuf == null) {
			return false;
		}
		SimpleFieldSet fs = om.validateNoderef(noderefBuf, 0, noderefLength, source, false);
		if(fs == null) {
			om.rejectRef(uid, source, DMT.NODEREF_REJECTED_INVALID, this);
			return false;
		}
		// If we want it, add it and send it.
		try {
			if(om.addNewOpennetNode(fs) != null) {
				sendOurRef(source, om.crypto.myCompressedFullRef());
			} else {
				if(logMINOR)
					Logger.minor(this, "Don't need the node");
				sendNotWanted();
				// Okay, just route it.
			}
		} catch (FSParseException e) {
			om.rejectRef(uid, source, DMT.NODEREF_REJECTED_INVALID, this);
			return false;
		} catch (PeerParseException e) {
			om.rejectRef(uid, source, DMT.NODEREF_REJECTED_INVALID, this);
			return false;
		} catch (ReferenceSignatureVerificationException e) {
			om.rejectRef(uid, source, DMT.NODEREF_REJECTED_INVALID, this);
			return false;
		} catch (NotConnectedException e) {
			Logger.normal(this, "Could not receive noderef, disconnected");
			return false;
		}
		return true;
	}

	private void sendNotWanted() throws NotConnectedException {
		Message msg = DMT.createFNPOpennetAnnounceNodeNotWanted(uid);
		source.sendAsync(msg, null, 0, this);
	}

	private void sendOurRef(PeerNode next, byte[] ref) throws NotConnectedException {
		om.sendAnnouncementReply(uid, next, ref, this);
	}

	private volatile Object totalBytesSync = new Object();
	private int totalBytesSent;
	
	public void sentBytes(int x) {
		synchronized(totalBytesSync) {
			totalBytesSent += x;
		}
	}
	
	public int getTotalSentBytes() {
		synchronized(totalBytesSync) {
			return totalBytesSent;
		}
	}
	
	private int totalBytesReceived;
	
	public void receivedBytes(int x) {
		synchronized(totalBytesSync) {
			totalBytesReceived += x;
		}
	}
	
	public int getTotalReceivedBytes() {
		synchronized(totalBytesSync) {
			return totalBytesReceived;
		}
	}

	public void sentPayload(int x) {
		// Doesn't count.
	}
	
}
