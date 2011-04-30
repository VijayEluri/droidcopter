package org.haldean.chopper.pilot;

import java.util.LinkedList;
import java.util.ListIterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

/**
 * Central "storehouse" for information about the chopper's status--maintains updated sensor readings, gps readings, etc.
 * <P>
 * May send the following messages to registered Receivables:<br>
 * <pre>
 * CSYS:LOWPOWER
 * GPS:STATUS:
 *            DISABLED
 *            ENABLED
 *            OUT.OF.SERVICE
 *            TEMPORARILY.UNAVAILABLE
 *            AVAILABLE
 * </pre>
 * @author Benjamin Bardin
 * @author Will Brown
 */
public final class ChopperStatus implements Runnable, SensorEventListener, Constants, LocationListener
{	
	/** Point of "officially" low battery */
	public final static int LOW_BATT = 30;
	
	/** Tag for logging */
	public static final String TAG = "chopper.ChopperStatus";
	
	/** Parameter to specify GPS minimum update distance, with the usual trade-off between accuracy and power consumption.
	 * Value of '0' means maximum accuracy. */
	private static final float GPS_MIN_DIST = 0;
	
	/** Parameter to specify GPS minimum update time, with the usual trade-off between accuracy and power consumption.
	 * Value of '0' means maximum accuracy. */
	private static final long GPS_MIN_TIME = 0;
	
	/** Number of threads in the mutator pool */
	private static final int sNumMutatorThreads = 5;
	
	/** Used to obtain location manager. */
	private Context mContext;
	
	/** Current battery level. */
	private final AtomicInteger mCurrBatt = new AtomicInteger(0);
	
	/** Holds GPS data. */
	private double[] mGps = new double[GPS_FIELDS]; //last available GPS readings
	
	/** Accuracy of last GPS reading.  This field is only accessed by one thread, so no lock is needed. */
	private float mGpsAccuracy; //accuracy of said reading
	
	/** Lock for gps extras */
	private ReentrantLock mGpsExtrasLock;
	
	/** Locks for fields in gps[]. */
	private ReentrantLock[] mGpsLock;
	
	/** Number of satellites used to obtain last GPS reading.  This field is only accessed by one thread, so no lock is needed. */
	private int mGpsNumSats; //number of satellites used to collect last reading
	
	/** Timestamp of last GPS reading.  This field is only accessed by one thread, so no lock is needed. */
	private long mGpsTimeStamp; //timestamp of the reading
	
	/** Stores the location object last returned by the GPS. */
	private Location mLastLoc;
	
	/** Max battery level. */
	private final AtomicInteger mMaxBatt = new AtomicInteger(100);

	/** Lock for motorspeed[]. */
	private ReentrantLock mMotorLock;
	
	/** Stores the speeds last submitted to the motors. */
	private double[] mMotorSpeed = new double[4];
	
	/** Lock for mMotorPower[] */
	private ReentrantLock mMotorPowerLock;
	
	/** Stores power levels for the motors. */
	private double[] mMotorPower = new double[4];
	
	/** Thread pool for mutator methods */
	private ExecutorService mMutatorPool;
	
	/** Holds data from various sensors, like gyroscope, acceleration and magnetic flux. */
	private double[] mReading = new double[SENSORS]; //last known data for a given sensor

	/** Locks for fields in reading[]. */
	private ReentrantLock[] mReadingLock;
	
	/** List of registered receivers */
	private LinkedList<Receivable> mRec;
	
	/**
	 * Initializes the locks, registers application context for runtime use.
	 * @param mycontext Application context
	 */
	public ChopperStatus(Context mycontext)	{
		mContext = mycontext;
		mRec = new LinkedList<Receivable>();
		
		//Initialize the data locks
		mReadingLock = new ReentrantLock[SENSORS];
		
		for (int i = 0; i < SENSORS; i++) {
			mReadingLock[i] = new ReentrantLock();
		}
		
		mGpsLock = new ReentrantLock[GPS_FIELDS];
		for (int i = 0; i < GPS_FIELDS; i++) {
			mGpsLock[i] = new ReentrantLock();
		}
		
		mMotorLock = new ReentrantLock();
		mGpsExtrasLock = new ReentrantLock();
		mMotorPowerLock = new ReentrantLock();
		
		mMutatorPool = Executors.newFixedThreadPool(sNumMutatorThreads);
	}
	
	/**
	 * Gets the phone battery level
	 * @return The power level, as a float between 0 and 1.
	 */
	public float getBatteryLevel() {
		return (float) mCurrBatt.get() / (float) mMaxBatt.get();
	}
	
	/**
	 * Gets GPS "extras" data, through a string of the form accuracy:numberOfSatellitesInLatFix:timeStampOfLastFix
	 * @return The data string
	 * @throws IllegalAccessException If the lock is currently in use.
	 */
	public String getGpsExtrasNow() throws IllegalAccessException {
		String gpsData = "";
		try {
			if (mGpsExtrasLock.tryLock(0, TimeUnit.SECONDS)) {
				gpsData += mGpsAccuracy + 
				":" + mGpsNumSats +
				":" + mGpsTimeStamp;
				mGpsExtrasLock.unlock();
			}
			else {
				throw new InterruptedException();
			}
		}
		catch (InterruptedException e) {
			throw new IllegalAccessException();
		}
		return gpsData;
	}
	
	/**
	 * Returns the value stored at the specified GPS index.  If its lock is unavailable, blocks until it is.
	 * @param whichField The index of the desired GPS data.
	 * @return The desired GPS data.
	 */
	public double getGpsField(int whichField) {
		double myValue;
		mGpsLock[whichField].lock();
		myValue = mGps[whichField];
		mGpsLock[whichField].unlock();
		return myValue;
	}
	
	/**
	 * Returns the value stored at the specified GPS index, if its lock is available.  Otherwise, throws an exception.
	 * @param whichField The index of the desired GPS data.
	 * @return The desired GPS data.
	 * @throws IllegalAccessException If the lock for the desired data is unavailable.
	 */
	public double getGpsFieldNow(int whichField) throws IllegalAccessException {
		double myValue;
		try {
			if (mGpsLock[whichField].tryLock(0, TimeUnit.SECONDS)) {
				myValue = mGps[whichField];
				mGpsLock[whichField].unlock();
			}
			else {
				throw new InterruptedException();
			}
		}
		catch (InterruptedException e) {
			Log.w(TAG, "GPS Field " + whichField + " is locked.");
			throw new IllegalAccessException();
		}
		return myValue;
	}
	
	/**
	 * Returns the value stored at the specified GPS index, if its lock is available.  Otherwise, returns the supplied default value.
	 * @param whichField The index of the desired GPS data.
	 * @param expectedValue The default to return, should its lock be unavailable.
	 * @return Either the GPS data or the supplied default, depending on whether or not its lock is available.
	 */
	public double getGpsFieldNow(int whichField, double expectedValue) {
		double myValue;
		try {
			if (mGpsLock[whichField].tryLock(0, TimeUnit.SECONDS)) {
				myValue = mGps[whichField];
				mGpsLock[whichField].unlock();
			}
			else {
				throw new InterruptedException();
			}
		}
		catch (InterruptedException e) {
			myValue = expectedValue;
		}
		return myValue;
	}
	
	/**
	 * Returns a copy of the most recent Location object delivered by the GPS.
	 * @return The Location, if there is one; null if the GPS has not yet obtained a fix.
	 */
	public Location getLastLocation() {
		if (mLastLoc == null)
			return null;
		Location myLocation;
		synchronized (mLastLoc) {
			myLocation = new Location(mLastLoc);
		}
		return myLocation;
	}
	
	/**
	 * Obtains the current motor speeds.
	 * @return A copy of the array containing the motor speeds.
	 * @throws IllegalAccessException If the lock is unavailable.
	 */
	public double[] getMotorFieldsNow() throws IllegalAccessException {
		double[] myValues = new double[4];
		try {
			if (mMotorLock.tryLock(0, TimeUnit.SECONDS)) {
				for (int i = 0; i < 4; i++) {
					myValues[i] = mMotorSpeed[i];
				}
				mMotorLock.unlock();
			}
			else {
				throw new InterruptedException();
			}
		}
		catch (InterruptedException e) {
			Log.w(TAG, "motorspeed is locked.");
			throw new IllegalAccessException();
		}
		return myValues;
	}
	
	/** Obtains the current motor power levels.
	 * @return A copy of the array containing the motor power levels.
	 * @throws IllegalAccessException If the lock is unavailable.
	 */
	public double[] getMotorPowerFieldsNow() throws IllegalAccessException {
		double[] myValues = new double[4];
		try {
			if (mMotorPowerLock.tryLock(0, TimeUnit.SECONDS)) {
				for (int i = 0; i < 4; i++) {
					myValues[i] = mMotorPower[i];
				}
				mMotorPowerLock.unlock();
			}
			else {
				throw new InterruptedException();
			}
		}
		catch (InterruptedException e) {
			Log.w(TAG, "motorpowers is locked.");
			throw new IllegalAccessException();
		}
		return myValues;
	}
	
	/**
	 * Runs the Thread
	 */
	public void run()
	{
		//System.out.println("ChopperStatus run() thread ID " + getId());
		Looper.prepare();
		Thread.currentThread().setName("ChopperStatus");
		
        /* Register to receive battery status updates */
        BroadcastReceiver batteryInfo = new BroadcastReceiver() {
			public void onReceive(Context context, Intent intent) {
				String action = intent.getAction();
				if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
					/* int read/writes are uninterruptible, no lock needed */
					mCurrBatt.set(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0));
					mMaxBatt.set(intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
					float batteryPercent = (float) mCurrBatt.get() * 100F / (float) mMaxBatt.get();
					if (batteryPercent <= LOW_BATT) {
						updateReceivers("CSYS:LOWPOWER");
					}
				}
			}
        };
	
        /* Gets a sensor manager */
		SensorManager sensors = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
		
		/* Registers this class as a sensor listener for every necessary sensor. */
		sensors.registerListener(this, sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL);
		//sensors.registerListener(this, sensors.getDefaultSensor(Sensor.TYPE_LIGHT), SensorManager.SENSOR_DELAY_NORMAL);
		//sensors.registerListener(this, sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), SensorManager.SENSOR_DELAY_NORMAL);
		sensors.registerListener(this, sensors.getDefaultSensor(Sensor.TYPE_ORIENTATION), SensorManager.SENSOR_DELAY_FASTEST);
		//sensors.registerListener(this, sensors.getDefaultSensor(Sensor.TYPE_PRESSURE), SensorManager.SENSOR_DELAY_NORMAL);
		//sensors.registerListener(this, sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY), SensorManager.SENSOR_DELAY_NORMAL);
		sensors.registerListener(this, sensors.getDefaultSensor(Sensor.TYPE_TEMPERATURE), SensorManager.SENSOR_DELAY_NORMAL);

		/* Initialize GPS reading: */
		LocationManager LocMan = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
		LocMan.requestLocationUpdates(LocationManager.GPS_PROVIDER,	GPS_MIN_TIME,	GPS_MIN_DIST,	this);
		
		mContext.registerReceiver(batteryInfo, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

		Looper.loop();
	}
	
	public double getReadingField(int whichField) {
		double myValue;
		mReadingLock[whichField].lock();
		myValue = mReading[whichField];
		mReadingLock[whichField].unlock();
		return myValue;
	}
	
	/**
	 * Returns the reading specified by the supplied index, if its lock is available.  Otherwise, throws an exception.
	 * @param whichField The index of the desired reading.
	 * @return The desired reading.
	 * @throws IllegalAccessException If the lock for the desired reading is unavailable.
	 */
	public double getReadingFieldNow(int whichField) throws IllegalAccessException {
		double myValue;
		try {
			if (mReadingLock[whichField].tryLock(0, TimeUnit.SECONDS)) {
				myValue = mReading[whichField];
				mReadingLock[whichField].unlock();
			}
			else {
				throw new InterruptedException();
			}
		}
		catch (InterruptedException e) {
			Log.w(TAG, "Reading field " + whichField + " is locked.");
			throw new IllegalAccessException();
		}
		return myValue;
	}
	
	/**
	 * Returns the reading specified by the supplied index, if its lock is available.  Otherwise, returns the supplied default value.
	 * @param whichField The index of the desired reading.
	 * @param expectedValue The default to return, should its lock be unavailable.
	 * @return Either the reading or the supplied default, depending on whether or not its lock is available.
	 */
	public double getReadingFieldNow(int whichField, double expectedValue) {
		double myValue;
		try {
			if (mReadingLock[whichField].tryLock(0, TimeUnit.SECONDS)) {
				myValue = mReading[whichField];
				mReadingLock[whichField].unlock();
			}
			else {
				throw new InterruptedException();
			}
		}
		catch (InterruptedException e) {
			Log.e(TAG, "Reading field " + whichField + " is locked.");
			myValue = expectedValue;
		}
		return myValue;
	}
	
	/**
	 * Registers a change in sensor accuracy.  Not used in this application.
	 * @param sensor Sensor registering change in accuracy.
	 * @param newaccuracy New accuracy value.
	 */
	public void onAccuracyChanged(Sensor sensor, int newaccuracy) {
	}
	
	/**
	 * Changes the value of the local GPS fields based on the data contained in a new GPS fix.
	 * @param loc New GPS fix
	 */
	public void onLocationChanged(Location loc) {
		if (loc != null && mGps != null) {
			if (!loc.hasAltitude()) {
			//	loc.setAltitude(300.0);
				Log.w(TAG, "No altitude fix");
			}
			double newalt = loc.getAltitude();
			System.out.println("new altitude: " + newalt);
			/* Vertical velocity does not update until vertical position does; prevents false conclusions that vertical velocity == 0 */
			double oldAlt = getGpsField(ALTITUDE);
			if (newalt != oldAlt) {
				mGpsExtrasLock.lock();
				long timeElapsed = mGpsTimeStamp - loc.getTime();
				mGpsExtrasLock.unlock();
				
				setGpsField(dALT, (newalt - oldAlt / (double) timeElapsed) * 1000.0);
			}
			
			setGpsField(ALTITUDE, newalt);
			setGpsField(BEARING, loc.getBearing());
			setGpsField(LONG, loc.getLongitude());
			setGpsField(LAT, loc.getLatitude());			
			setGpsField(SPEED, loc.getSpeed());
			
			mGpsExtrasLock.lock();
			mGpsAccuracy = loc.getAccuracy();
			mGpsTimeStamp = loc.getTime();
			if (loc.getExtras() != null)
				mGpsNumSats = loc.getExtras().getInt("satellites");
			mGpsExtrasLock.unlock();
			if (mLastLoc != null) {
				synchronized (mLastLoc) {
					mLastLoc = loc;
				}
			}
			else {
				mLastLoc = loc;
			}
		}
	}
	
	/**
	 * Informs all receivers that a GPS provider is disabled.
	 * @param provider The name of the provider that has been disabled
	 */
	public void onProviderDisabled(String provider)	{
		System.out.println("GPS disabled");
		updateReceivers("GPS:STATUS:DISABLED");
	}
	
	/**
	 * Informs all receivers that a GPS provider is enabled.
	 * @param provider The name of the provider that has been enabled
	 */
	public void onProviderEnabled(String provider) {
		System.out.println("GPS enabled");
		updateReceivers("GPS:STATUS:ENABLED.");
	}
	
	/**
	 * Registers a change in sensor data.
	 * @param event Object containing new data--sensor type, accuracy, timestamp and value.
	 */
	public void onSensorChanged(SensorEvent event) {
		int type = event.sensor.getType();
		switch (type) {
			case Sensor.TYPE_ACCELEROMETER:
				setReadingField(X_ACCEL, event.values[0]);
				setReadingField(Y_ACCEL, event.values[1]);
				setReadingField(Z_ACCEL, event.values[2]);
				break;
			case Sensor.TYPE_LIGHT:
				setReadingField(LIGHT, event.values[0]);
				break;
			case Sensor.TYPE_MAGNETIC_FIELD:
				setReadingField(X_FLUX, event.values[0]);
				setReadingField(Y_FLUX, event.values[1]);
				setReadingField(Z_FLUX, event.values[2]);
				break;
			case Sensor.TYPE_ORIENTATION:
				setReadingField(AZIMUTH, event.values[0]);
				setReadingField(PITCH, event.values[1]);
				setReadingField(ROLL, event.values[2]);
				break;
			case Sensor.TYPE_PRESSURE:
				setReadingField(PRESSURE, event.values[0]);
				break;
			case Sensor.TYPE_PROXIMITY:
				setReadingField(PROXIMITY, event.values[0]);
				break;
			case Sensor.TYPE_TEMPERATURE:
				setReadingField(TEMPERATURE, event.values[0]);
				break;
		}
	}
	
	/**
	 * Informs all receivers that a GPS provider's status has changed.
	 * @param provider The name of the provider whose status has changed
	 * @param status The new status of the provider
	 * @param extras Provider-specific extra data
	 */
	public void onStatusChanged(String provider, int status, Bundle extras)	{
		System.out.println("GPS status changed " + status);
		switch (status) {
			case LocationProvider.OUT_OF_SERVICE:
				updateReceivers("GPS:STATUS:OUT.OF.SERVICE");
				break;
			case LocationProvider.TEMPORARILY_UNAVAILABLE:
				updateReceivers("GPS:STATUS:TEMPORARILY.UNAVAILABLE");
				break;
			case LocationProvider.AVAILABLE:
				updateReceivers("GPS:STATUS:AVAILABLE");
				break;
		}
	}
	
	/**
	 * Registers a receivable for certain status-related updates, especially a Navigation object.
	 * @param rec The receivable to register.
	 * @see Navigation Navigation
	 */
	public void registerReceiver(Receivable rec) {
		synchronized (mRec) {
			mRec.add(rec);
		}
	}
	
	/**
	 * Sets the motor speed data to the supplied array.
	 * @param mySpeeds The data to which the motor speeds should be set.
	 */
	protected void setMotorFields(double[] mySpeeds) {
		if (mySpeeds.length < 4)
			return;
		final double[] speeds = mySpeeds.clone();
		mMutatorPool.submit(new Runnable() {
			public void run() {
				//Log.v(TAG, "Changing motorspeeds:");
				
				mMotorLock.lock();
				for (int i = 0; i < 4; i++) {
					mMotorSpeed[i] = speeds[i];
				}
				/*Log.v(TAG, "vector " + mMotorSpeed[0] + ", "
									 + mMotorSpeed[1] + ", "
									 + mMotorSpeed[2] + ", "
									 + mMotorSpeed[3]);*/
				mMotorLock.unlock();
				//Log.v(TAG, "Done changing motorspeeds");
			}
		});
	}
	
	/**
	 * Sets the motor power levels to the supplied array.
	 * @param myPowers The data to which the motor power levels should be set.
	 */
	protected void setMotorPowerFields(double[] myPowers) {
		if (myPowers.length < 4)
			return;
		final double[] powers = myPowers.clone();
		mMutatorPool.submit(new Runnable() {
			public void run() {
				mMotorPowerLock.lock();
				for (int i = 0; i < 4; i++) {
					mMotorPower[i] = powers[i];
				}
				mMotorPowerLock.unlock();	
			}
		});
	}
	
	/**
	 * Writes the supplied GPS value at the specified GPS field. 
	 * @param whichField The index of the GPS data to store.
	 * @param value The GPS data.
	 */
	private void setGpsField(final int whichField, final double value) {
		mMutatorPool.submit(new Runnable() {
			public void run() {
				mGpsLock[whichField].lock();
				mGps[whichField] = value;
				mGpsLock[whichField].unlock();
			}
		});
	}
	
	
	/**
	 * Writes the supplied reading at the specified field. 
	 * @param whichField The index of the reading to store.
	 * @param value The reading.
	 */
	private void setReadingField(final int whichField, final double value) {
		mMutatorPool.submit(new Runnable() {
			public void run() {
				mReadingLock[whichField].lock();
				mReading[whichField] = value;
				mReadingLock[whichField].unlock();
			}
		});
	}
	
	/** Updates all registered receivers with the specified String */
	private void updateReceivers(String str) {
		synchronized (mRec) {
			ListIterator<Receivable> myList = mRec.listIterator();
			while (myList.hasNext()) {
				myList.next().receiveMessage(str, null);
			}
		}
	}
}