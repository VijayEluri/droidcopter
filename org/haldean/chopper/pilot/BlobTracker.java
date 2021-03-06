package org.haldean.chopper.pilot;

import org.haldean.blob.AndroidImage;
import org.haldean.blob.Image;
import org.haldean.blob.Segmenter;

public final class BlobTracker implements Runnable, Receivable {
    Image image;
    Segmenter segmenter;
    int[] lastLocation;
    int[] lastVector;
    MakePicture mPic;
    byte[] mBuffer;
    boolean enabled;

    private static final int TRACKING_PERIOD_MS = 200;
    private static final int DISABLED_PERIOD_MS = 1000;
    private static final int ASCEND_VELOCITY = 2;

    public BlobTracker(MakePicture pic) {
    	lastLocation = new int[2];
    	lastVector = new int[3];
    	segmenter = null;
    	image = new AndroidImage();
    	mPic = pic;
    	mBuffer = new byte[mPic.getBufferLength()];
    }

    public void receiveMessage(String msg, Receivable source) {
    	if (msg.startsWith("SEGMENT")) {
	    segmenter = Segmenter.fromString(msg);
    	}
    }

    public int[] getVector() {
    	
    	int[] vector = new int[] { lastVector[0], lastVector[1], lastVector[2] };
    	return vector;
    }

    public void setEnabled(boolean enabled) {
	this.enabled = enabled;
    }

    private void calculateVector() {
	if (segmenter == null || image == null) return;
	synchronized (image) {
	    int[] imageSize = image.getSize();
	    lastLocation = segmenter.segment(image);
	    
	    if (lastLocation != null) {
		synchronized (lastVector) {
		    lastVector[0] = lastLocation[0] - imageSize[0] / 2;
		    lastVector[1] = lastLocation[1] - imageSize[1] / 2;
		    lastVector[2] = 0;
		}
	    } else {
		lastVector = new int[] { 0, 0, ASCEND_VELOCITY };
	    }
	}
    }
    
    public void run() {
	while (true) {
	    if (enabled) {
		if (mBuffer.length != mPic.getBufferLength())
		    mBuffer = new byte[mPic.getBufferLength()];
		mPic.getBufferCopy(mBuffer);
		int[] size = mPic.getFrameSize();
		image.updateImage(mBuffer, size[0], size[1]);
		calculateVector();
		try {
		    Thread.sleep(TRACKING_PERIOD_MS);
		} catch (InterruptedException e) {
		    e.printStackTrace();
		}
	    } else {
		try {
		    Thread.sleep(DISABLED_PERIOD_MS);
		} catch (InterruptedException e) {
		    e.printStackTrace();
		}
	    }
	}
    }
}