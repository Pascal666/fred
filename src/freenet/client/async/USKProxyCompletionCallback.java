/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.keys.FreenetURI;
import freenet.keys.USK;

public class USKProxyCompletionCallback implements GetCompletionCallback {

	final USK usk;
	final GetCompletionCallback cb;
	final boolean persistent;
	
	public USKProxyCompletionCallback(USK usk, GetCompletionCallback cb, boolean persistent) {
		this.usk = usk;
		this.cb = cb;
		this.persistent = persistent;
	}

	public void onSuccess(FetchResult result, ClientGetState state, ObjectContainer container, ClientContext context) {
		if(container != null && persistent) {
			container.activate(cb, 1);
			container.activate(usk, 5);
		}
		context.uskManager.update(usk, usk.suggestedEdition, context);
		cb.onSuccess(result, state, container, context);
	}

	public void onFailure(FetchException e, ClientGetState state, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(cb, 1);
			container.activate(usk, 5);
		}
		FreenetURI uri = e.newURI;
		if(uri != null) {
			uri = usk.turnMySSKIntoUSK(uri);
			e = new FetchException(e, uri);
		}
		cb.onFailure(e, state, container, context);
	}

	public void onBlockSetFinished(ClientGetState state, ObjectContainer container, ClientContext context) {
		if(container != null && persistent)
			container.activate(cb, 1);
		cb.onBlockSetFinished(state, container, context);
	}

	public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
		// Ignore
	}

	public void onExpectedMIME(String mime, ObjectContainer container) {
		if(container != null && persistent)
			container.activate(cb, 1);
		cb.onExpectedMIME(mime, container);
	}

	public void onExpectedSize(long size, ObjectContainer container) {
		if(container != null && persistent)
			container.activate(cb, 1);
		cb.onExpectedSize(size, container);
	}

	public void onFinalizedMetadata(ObjectContainer container) {
		if(container != null && persistent)
			container.activate(cb, 1);
		cb.onFinalizedMetadata(container);
	}

}
