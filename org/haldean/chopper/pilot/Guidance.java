package org.haldean.chopper.pilot;

import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedList;
import java.util.ListIterator;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

/**
 * Determines motor speeds, based on chopper's status and desired velocity vector.
 * 
 * May send the following messages to registered Receivables:<br>
 * <pre>
 * GUID:ERROR:&lt;loop_1_error&gt;:&lt;loop_2_error&gt;:&lt;loop_3_error&gt;:&lt;loop_4_error&gt;
 * GUID:PID:VALUE:&lt;pid_loop_number&gt;:&lt;pid_parameter_index&gt;:&lt;pid_parameter_value&gt;
 * </pre>
 * 
 * May receive the following messages from Chopper components:
 * <pre>
 * GUID:
 *      PID:
 *          SET:&lt;pid_loop_number&gt;:&lt;pid_parameter_index&gt;:&lt;pid_parameter_value&gt;
 *          GET
 *      AUTOMATIC
 *      VECTOR:&lt;north_motor_speed&gt;:&lt;south_motor_speed&gt;:&lt;east_motor_speed&gt;:&lt;west_motor_speed&gt;
 * 		LOCALVEC
 * 		ABSVEC
 * 
 * </pre>
 * 
 * @author Benjamin Bardin
 */
public class Guidance implements Runnable, Constants, Receivable {
	
	/** How many times per second the PID loop will run */
	public static final int PIDREPS = 10;
	
	/** Maximum permissible target velocity, in m/s; larger vectors will be resized */
	public static final double MAX_VEL = 2.0;
	
	/** The maximum angle, in degrees, guidance will permit the chopper to have */
	public static final double MAX_ANGLE = 10;
	
	/** The maximum change in motor speed permitted at one time.  Must be positive. */
	public static final double MAX_DMOTOR = .01;
	
	/** The maximum change in motor speed permitted at one time if the chopper is stabilizing.  Must be positive. */
	public static final double MAX_DSTABLE = .05;
	
	/** Tag for logging */
	public static final String TAG = new String("chopper.Guidance");
	
	/** Used when a really big number is needed, still small enough to prevent overflow. */
	private static final double sReallyBig = 2.0;
	
	/** Handles messages for the thread */
	private Handler mHandler;
	
	/** Stores whether or not a motor-eval loop should add itself to the queue again. **/
	private boolean mReviseMotorSpeeds = true;
	
	/** Stores orientation data persistently, as expected values in case lock is not immediately available*/
	private double mAzimuth;
	private double mPitchDeg;
	private double mRollDeg;
	private double mGpsBearing;
	private double mGpsSpeed;
	private double mGpsDalt;
	
	public static final String logname = "/sdcard/chopper/guidlog.txt";
	private FileWriter logfile;
	
	/** Note that some of the following objects are declared outside their smallest scope.
	 * This is to relieve unnecessary stress on the GC.  Many of these data holders
	 * can easily be reused from iteration to iteration, and since the PID loops
	 * may run as much as 20+ times a second this is considerably more efficient,
	 * though somewhat less readable.  As a compromise, primitives are declared/
	 * initialized each time in their scope, and reusable objects (especially arrays)
	 * remain persistent from iteration to iteration. 
	 */
	
	/** Stores desired velocity */
	private double[] mTarget = new double[4];
	
	/** Stores the current velocity (relative to the chopper) */
	private double[] mCurrent = new double[4];
	
	/** Stores current PID error */
	private double[][] mErrors = new double[4][3];
	
	/** Manages integral error */
	private int mIntegralIndex = 0;
	private double[][] mIntegralErrors = new double[4][PIDREPS];
	
	/** Timestamp of last PID evaluation */
	private long mLastUpdate = 0;
	
	/** Sum of errors * tuning parameter for a given PID loop */
	private double[] mTorques = new double[4];
	
	/** Stores motor speeds temporarily */
	private double[] mTempMotorSpeed = new double[4];
	
	/** Tuning parameters */
	private double[][] mGain = new double[4][3];
	
	/** Motor speed */
	private double[] mMotorSpeed = new double[4]; //ORDER: North, South, East, West
	
	/** If set to true, disregards lateral velocity commands
	 * Currently unused, though later may implement as extra safety protocol
	 * in the event of difficulty maintaining altitude */
	private boolean mHorizontalDrift = false; //if true, does not consider dx, dy or azimuth error; makes for maximally efficient altitude control
	
	private LinkedList<Receivable> mRec;
	
	/** Handles to other chopper components */
	private ChopperStatus mStatus;
	private Navigation mNav;
	
	/** Controls whether N/S and E/W commands refer to absolute vectors or local **/
	private boolean mAbsVec = true;
	
	private BluetoothOutput mBt;
	
	public final static boolean mEnableLogging = true;
	/**
	 * Constructs a Guidance object
	 * @param status The source status information.
	 * @param nav The source of navigation target information.
	 */
	public Guidance(ChopperStatus status, Navigation nav, BluetoothOutput bT) {
		if (status == null | nav == null) {
			throw new NullPointerException();
		}
		mStatus = status;
		mNav = nav;
		mRec = new LinkedList<Receivable>();
		mBt = bT;
		
		//Temporary: need real tuning values at some point. Crap.
		for (int i = 0; i < 2; i++)
			for (int j = 0; j < 3; j++)
				mGain[i][j] = .0003;
				//mGain[i][j] = .05;
		for (int j = 0; j < 3; j++) {
		//	mGain[3][j] = .0005;
			mGain[2][j] = .0015;
			mGain[3][j] = 0;
		}
		try {
			if (mEnableLogging)
				logfile = new FileWriter(logname, false);
		}
		catch (IOException e) {
			e.printStackTrace();
			Log.e(TAG, "Cannot open log file.");
		}
	}
	
	private String getErrorString() {
		return "GUID:ERROR:" + mErrors[0][0]
		               + ":" + mErrors[1][0]
		               + ":" + mErrors[2][0]
		               + ":" + mErrors[3][0];
	}
	
	public void onDestroy() {
		try {
			if (logfile != null)
				logfile.close();
		}
		catch (IOException e) {
			Log.e(TAG, "Cannot close logfile.");
		}
	}
	
	/**
	 * Starts the guidance thread
	 */
	public void run() {
		Looper.prepare();
		Thread.currentThread().setName("Guidance");
		Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
		mHandler = new Handler() {
			public void handleMessage(Message msg) {
				switch (msg.what) {
				case EVAL_MOTOR_SPEED:
					reviseMotorSpeed();
					//Log.d(TAG, getErrorString());
					updateReceivers(getErrorString());
					break;
				case NEW_PID_VALUE:
					mGain[msg.arg1][msg.arg2] = (Double)msg.obj;
					break;
				case NEW_GUID_VECTOR:
					Double[] mVector = (Double[])msg.obj;
					for (int i = 0; i < 4; i++) {
						mMotorSpeed[i] = mVector[i];
					}
					updateMotors();
					break;
				case GET_PIDS:
					Receivable source = (Receivable) msg.obj;
					
					//Send each PID value to the requesting object
					for (int i = 0; i < 4; i++) {
						for (int j = 0; j < 3; j++) {
							source.receiveMessage("GUID:PID:VALUE:" +
												  i + ":" + j + ":" +
												  mGain[i][j],
												  null);
									
						}
					}							
					break;
				}
			}
		};
		//mHandler.sendEmptyMessage(EVAL_MOTOR_SPEED);
		receiveMessage("GUID:VECTOR:0:0:0:0", null);
		Looper.loop();
	}
	
	/**
	 * Receive a message.
	 * @param msg The message to process.
	 * @param source The source of the message, if a reply is needed.  May be null.
	 */
	public void receiveMessage(String msg, Receivable source) {
		//Log.d(TAG, "Receiving message " + msg);
		String[] parts = msg.split(":");
		if (parts[0].equals("GUID")) {
			if (parts[1].equals("PID")) {
				if (parts[2].equals("SET")) {
					Message newValue = Message.obtain(mHandler,
													  NEW_PID_VALUE,
													  new Integer(parts[3]),
													  new Integer(parts[4]), 
													  new Double(parts[5]));
					newValue.sendToTarget();
				}
				if (parts[2].equals("GET")) {
					Message getPids = Message.obtain(mHandler, GET_PIDS, source);
					getPids.sendToTarget();
				}
			}
			if (parts[1].equals("AUTOMATIC")) {
				mHandler.removeMessages(NEW_GUID_VECTOR);
				mReviseMotorSpeeds = true;
				if (!mHandler.hasMessages(EVAL_MOTOR_SPEED))
					mHandler.sendEmptyMessage(EVAL_MOTOR_SPEED);
			}
			if (parts[1].equals("LOCALVEC")) {
				Log.v(TAG, "Setting mabsvec to false");
				mAbsVec = false;
			}
			if (parts[1].equals("ABSVEC")) {
				Log.v(TAG, "Setting mabsvec to true");
				mAbsVec = true;
			}
			if (parts[1].equals("VECTOR")) {
				mReviseMotorSpeeds = false;
				mHandler.removeMessages(EVAL_MOTOR_SPEED);
				Double[] myVector = new Double[4];
				for (int i = 0; i < 4; i++) {
					myVector[i] = new Double(parts[i + 2]);
				}
				Message newValue = Message.obtain(mHandler, NEW_GUID_VECTOR, myVector);
				newValue.sendToTarget();
			}
		}
	}
	
	/**
	 * Registers a receiver to receive Guidance updates.
	 * @param rec
	 */
	public void registerReceiver(Receivable rec) {
		synchronized (mRec) {
			mRec.add(rec);
		}
	}
	
	/** Core of the class; calculates new motor speeds based on status */
	private void reviseMotorSpeed() {
		mHandler.removeMessages(EVAL_MOTOR_SPEED);
		//Log.v(TAG, "START MOTOR REVISION");
		long starttime = System.currentTimeMillis();
		
		//Copying motor values to temporary array for working purposes.
		for (int i = 0; i < 4; i++) {
			mTempMotorSpeed[i] = mMotorSpeed[i];
		}
		
		boolean mStabilizing = false; //initializing value
		//Retrieve current orientation.		
		
		mAzimuth = mStatus.getReadingFieldNow(AZIMUTH, mAzimuth);		
		mPitchDeg = -mStatus.getReadingFieldNow(PITCH, -mPitchDeg);
		mRollDeg = mStatus.getReadingFieldNow(ROLL, mRollDeg);
		
		double pitchrad = mPitchDeg * Math.PI / 180.0;
		double rollrad = mRollDeg * Math.PI / 180.0;
		
		double gradient = Math.sqrt(
				Math.pow(Math.tan(rollrad), 2) +
				Math.pow(Math.tan(pitchrad), 2)
				);
		double ascentrad = Math.atan(gradient);
		double mAscentDeg = ascentrad * 180.0 / Math.PI;
		//if orientation is out-of-bounds,
		if ((mAscentDeg > MAX_ANGLE) | (mPitchDeg > 90.0) | (mPitchDeg < -90.0)) {
			mStabilizing = true;
			//set target velocity to some big number in the direction of maximum ascent
			double gradangle = Math.atan2(
						Math.tan(rollrad) ,
						Math.tan(pitchrad)
						);
			mTarget[0] = sReallyBig * Math.sin(gradangle);
			mTarget[1] = sReallyBig * Math.cos(gradangle);
			
			//Make sure the velocity vector components point in the right directions.
			mTarget[0] *= Math.signum(mTarget[0]) * Math.signum(mRollDeg);
			mTarget[1] *= Math.signum(mTarget[1]) * Math.signum(mPitchDeg);
			mTarget[2] = 0;
			mTarget[3] = mAzimuth;
			//System.out.println(target[0] + ", " + target[1]);
		}
		else {
			//Retrieve target velocity from nav,
			//Transform absolute target velocity to relative target velocity
			double theta = mAzimuth * Math.PI / 180.0;
			if (mNav != null) {
				try {
					double[] absTarget = mNav.getTarget();
					if (mAbsVec) {
						//Log.v(TAG, "Abs vectors");
						mTarget[0] = absTarget[0] * Math.cos(theta) - absTarget[1] * Math.sin(theta);
						mTarget[1] = absTarget[0] * Math.sin(theta) + absTarget[1] * Math.cos(theta);
						mTarget[2] = absTarget[2];
						mTarget[3] = absTarget[3];
					}
					else {
						//Log.v(TAG, "Local vectors");
						String targ = "";
						for (int i = 0; i < 4; i++) {
							mTarget[i] = absTarget[i];
							targ = targ + " " + mTarget[i];
						}
						//Log.v(TAG, "guid, nav targ: " + targ);
					}
					
					//Calculate recorded velocity; reduce, if necessary, to MAXVEL
					double myVel = 0;
					for (int i = 0; i < 3; i++) {
						myVel += Math.pow(mTarget[i], 2);
					}
					myVel = Math.sqrt(myVel);
					if (myVel > MAX_VEL) {
						Log.v(TAG, "guid, Reducing requested velocity");
						double adjustment = MAX_VEL / myVel;
						for (int i = 0; i < 3; i++) {
							mTarget[i] *= adjustment;
						}
					}
				}
				catch (IllegalAccessException e) {
					Log.w(TAG, "Nav Target lock not available.");
				}
			}
			//Log.v(TAG, "Relative target: " + mTarget[0] + ", " + mTarget[1] + ", " + mTarget[2] + ", " + mTarget[3]);
		}
		
		
		
		long thistime = System.currentTimeMillis();
		
		//Retrieve current absolute velocity.  For now, only from GPS data; later, maybe write a kalman filter to use accelerometer data as well. 
		//Transform current velocity from absolute to relative
		
		if (mStabilizing) {
			mCurrent[0] = 0;
			mCurrent[1] = 0;
			mCurrent[2] = 0;
			mCurrent[3] = mAzimuth;
		}
		else {
			mGpsBearing = mStatus.getGpsFieldNow(BEARING, mGpsBearing);
			double theta = (mGpsBearing - mAzimuth) * Math.PI / 180.0;
			
			mGpsSpeed = mStatus.getGpsFieldNow(SPEED, mGpsSpeed);
			mCurrent[0] = mGpsSpeed * Math.sin(theta);
			mCurrent[1] = mGpsSpeed * Math.cos(theta);
			
			mGpsDalt = mStatus.getGpsFieldNow(dALT, mGpsDalt);
			mCurrent[2] = mGpsDalt;
			mCurrent[3] = mAzimuth;
			
			String targ = "guid, curr vec: ";
			for (int i = 0; i < 4; i++) {
				targ = targ + " " + mCurrent[i];
			}
			//Log.v(TAG, targ);
		}
		
		for (int i = 0; i < 4; i++) {
			//Calculate proportional errors
			double err = mTarget[i] - mCurrent[i];
			if (i == 3) { //For azimuth, multiple possibilities exist for error, each equally valid; but only the error nearest zero makes practical sense.
				if (err > 180.0)
					err -= 360.0;
				if (err < -180.0)
					err += 360.0;
			}

			//Calculate derivative errors.
			mErrors[i][2] = (err - mErrors[i][0]) * 1000.0 / (thistime - mLastUpdate);
			
			
			//Mark proportional error
			mErrors[i][0] = err;
			/*if (i == 2)
				Log.v(TAG, "guid, dalt err is " + err);*/
			//Update integral errors
			mErrors[i][1] -= mIntegralErrors[i][mIntegralIndex];
			mIntegralErrors[i][mIntegralIndex] = err;
			mErrors[i][1] += err;
			mIntegralIndex = ++mIntegralIndex % PIDREPS;
			
			double dmotor = 0;
			
			//Calculate changes in output
			for (int j = 0; j < 3; j++) {
			/*	if (i == 1) {
					Log.v(TAG, "guid, dy error " + j + " is " + mErrors[i][j]);
					Log.v(TAG, "guid, dy gain " + j + " is " + mGain[i][j]);
				}*/
				dmotor += mErrors[i][j] * mGain[i][j];
			}
			/*if (i == 1)
				Log.v(TAG, "guid, dmotor 1 is " + dmotor);
			if (i == 2)
				Log.v(TAG, "guid, dmotor 2 is " + dmotor);*/
			double phi = 0;
			switch (i) {
			case 0: //X velocity
				if (!mStabilizing) {
					phi = Math.sin(rollrad);
					phi = Math.abs(phi);
					if (phi == 0)
						dmotor = 2 * dmotor; 
					else
						dmotor = dmotor / phi;
				}
				break;
			case 1: //Y velocity
				if (!mStabilizing) {
					phi = Math.sin(pitchrad);
					phi = Math.abs(phi);
					if (phi == 0)
						dmotor = 2 * dmotor;
					else
						dmotor = dmotor / phi;
					Log.v(TAG, "guid, phi 1 is " + phi);
					Log.v(TAG, "guid, dmotor 1 is " + dmotor);
				}
				break;
			case 2: //Z velocity
				phi = Math.cos(ascentrad);
				phi = Math.abs(phi);
				if (phi == 0)
					dmotor = 0; //Don't bother with altitude control, gives more efficiency to torque[0, 1] for stabilization
				else
					dmotor = dmotor / phi;
				//Log.v(TAG, "guid, phi 2 is " + phi);
				break;
			case 3: //Azimuth
				break;
			}
			mTorques[i] = dmotor;
			//Log.v(TAG, "Torque " + i + " " + dmotor);
		}
		mLastUpdate = thistime;
		
		/*String targ = "";
		for (int i = 0; i < 4; i++)
			targ = targ + " " + mTorques[i];
		Log.v(TAG, "guid, torques is " + targ);
		*/
		if ((!mHorizontalDrift) || (mStabilizing)) { //if horizontal drift is on, motor speeds give full efficiency to altitude control
		//but if the chopper is stabilizing, under no circumstances ignore torques 0, 1
			//changes torques to motor values
			mTempMotorSpeed[0] -= mTorques[1] / 2.0;
			mTempMotorSpeed[1] += mTorques[1] / 2.0;
			
			//Log.v(TAG, "Temp 1 " + mTempMotorSpeed[2] + "\nTemp 2 " + mTempMotorSpeed[3]);
			mTempMotorSpeed[2] -= mTorques[0] / 2.0;
			mTempMotorSpeed[3] += mTorques[0] / 2.0;
			
			
			
			double spintorque = mTorques[3] / 4.0;
			mTempMotorSpeed[0] += spintorque;
			mTempMotorSpeed[1] += spintorque;
			mTempMotorSpeed[2] -= spintorque;
			mTempMotorSpeed[3] -= spintorque;
		}
		
		double dalttorque = mTorques[2] / 4.0;
		for (int i = 0; i < 4; i++) {
			mTempMotorSpeed[i] += dalttorque;
		}
		
		//Bounds Check--values must be between zero and one.
		for (int i = 0; i < 4; i++) {
			if (mTempMotorSpeed[i] < 0)
				mTempMotorSpeed[i] = 0;
			else if (mTempMotorSpeed[i] > 1)
				mTempMotorSpeed[i] = 1;
			double diff = mTempMotorSpeed[i] - mMotorSpeed[i];
			if (i==1)
				Log.v(TAG, "guid, diff is " + diff);
			if (mStabilizing) {
				if (diff > 0)
					mMotorSpeed[i] += Math.min(diff, MAX_DSTABLE);
				else if (diff < 0)
					mMotorSpeed[i] += Math.max(diff, -MAX_DSTABLE);
			}
			else {				
				if (diff > 0) {
					if (i==1)
						Log.v(TAG, "guid, adding " + Math.min(diff, MAX_DMOTOR));
					mMotorSpeed[i] += Math.min(diff, MAX_DMOTOR);
				}
				else if (diff < 0) {
					if (i==1)
						Log.v(TAG, "guid, adding " + Math.max(diff, -MAX_DMOTOR));
					mMotorSpeed[i] += Math.max(diff, -MAX_DMOTOR);
				}
			}
			mTempMotorSpeed[i] = mMotorSpeed[i];
			if (i == 1)
				Log.v(TAG, "guid, ms1 is " + mMotorSpeed[i]);
		}	
		
		//Send motor values to motors here:
		updateMotors();
		
		//Log.v(TAG, "motors: " + mMotorSpeed[0] + ", " + mMotorSpeed[1] + ", " + mMotorSpeed[2] + ", " + mMotorSpeed[3]);
		//Sleep a while
		long timetonext = (1000 / PIDREPS) - (System.currentTimeMillis() - starttime);
		if (mReviseMotorSpeeds) {
			if (timetonext > 0)
				mHandler.sendEmptyMessageDelayed(EVAL_MOTOR_SPEED, timetonext);
			else {
				Log.e(TAG, "Guidance too slow");
				mHandler.sendEmptyMessage(EVAL_MOTOR_SPEED);
			}
		}
	}
	
	/* To be finished */
	private void updateMotors() {
		//Pass filtered values to ChopperStatus.
		mStatus.setMotorFields(mMotorSpeed);
		String logline = Long.toString(System.currentTimeMillis()) + " " + mMotorSpeed[0] + " " + mMotorSpeed[1] + " " + mMotorSpeed[2] + " " + mMotorSpeed[3] + "\n";
		try {
			if (logfile != null) {
				logfile.write(logline);
				logfile.flush();
			}
		}
		catch (IOException e) {
			Log.e(TAG, "Cannot write to logfile");
		}
		//Pass motor values to motor controller!
		Message msg = Message.obtain(mBt.mHandler, SEND_MOTOR_SPEEDS, mMotorSpeed);
		msg.sendToTarget();
		//Log.i(TAG, "Guidance sending message.");
		
	}
	
	/**
	 * Updates all receivers
	 * @param str The message to send.
	 */
	private void updateReceivers(String str) {
		synchronized (mRec) {
			ListIterator<Receivable> myList = mRec.listIterator();
			while (myList.hasNext()) {
				myList.next().receiveMessage(str, this);
			}
		}
	}
}

