package freenet.client.async;

import freenet.node.Node;
import freenet.support.RandomGrabArrayItem;
import freenet.support.SectoredRandomGrabArrayWithInt;

/**
 * A low-level request which can be sent immediately. These are registered
 * on the ClientRequestScheduler.
 */
public interface SendableRequest extends RandomGrabArrayItem {
	
	public short getPriorityClass();
	
	public int getRetryCount();
	
	/** ONLY called by RequestStarter */
	public void send(Node node);
	
	/** Get client context object */
	public Object getClient();
	
	/** Get the ClientRequest */
	public ClientRequest getClientRequest();

}
