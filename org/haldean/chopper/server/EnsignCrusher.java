package org.haldean.chopper.server;

import org.haldean.chopper.nav.NavDest;
import org.haldean.chopper.nav.NavTask;
import org.haldean.chopper.nav.NavVel;

import gov.nasa.worldwind.geom.Position;

/**
 *  This is the class responsible for sending navigation commands to
 *  the helicopter. Right now, it can only send the helicopter
 *  messages to tell it a target velocity or location, although
 *  eventually this will be the place where it creates lists of tasks
 *  for the helicopter to complete.
 *
 *  @author William Brown
 */
public class EnsignCrusher {
    private static String navGoToManual = "NAV:SET:MANUAL";
    private static String navGoToAutomatic = "NAV:SET:AUTOPILOT";
    private static double bearing = 0;

    /* In meters per second (we hope.) */
    private static final int VELOCITY = 2;
    /* In seconds */
    private static final int STEP_DURATION = 1000;

    /**
     *  This is a convenience class that is never instantiated.
     */
    private EnsignCrusher() {
	/* You'll never reach me in here! AAAH HA HAA. */
    }

    /**
     *  Tell the helicopter to go at a specific velocity in a specific
     *  direction.
     *
     *  @param velocities An array whose values correspond to the North,
     *  South, East and West portions of the desired velocity.
     *
     *  @param duration The amount of time in milliseconds that the
     *  chopper should stay on this course before going back to
     *  hovering.
     */
    public static void setVelocity(double[] velocities, long duration) {
	makeItSo(NavVel.taskFor(velocities, duration));
    }

    /**
     *  Instead of passing chopper a NavTask, just send it a vector
     *  with target velocities and a target bearing that it will go at
     *  until it receives a different command
     *
     *  @param velocities An array whose values correspond to dx, dy
     *  and dz in meters per second.
     *  @param bearing The bearing to travel at, in right-hand degrees
     *  from North.
     */
    public static void manualVelocity(double[] velocities, double bearing) {
	EnsignCrusher.bearing = bearing;
	String command = navGoToManual;

	for (double v : velocities) {
	    command += ":" + v;
	}
	DataReceiver.sendToDefault(command + ":" + bearing);
    }

    /**
     *  Tell the helicopter to travel to a specific location.
     *
     *  @param location The target latitude, longitude and altitude.
     */
    public static void setTargetLocation(Position location, double velocity,
					 double targetRadius) {
	makeItSo(NavDest.taskFor(location.getElevation(), 
				 location.getLongitude().getDegrees(),
				 location.getLatitude().getDegrees(), 
				 velocity, targetRadius));
    }

    /**
     *  Send raw motor speed values to the chopper.
     *
     *  @param speeds An array full of magic, where the values
     *  correspond to the new speeds for the "north", "south", "east"
     *  and "west" motors.
     *
     *  Note that the motor "directions" aren't directions at
     *  all. They're convenient ways of describing which motor is
     *  which. Changing the "north" motor doesn't mean you are changing
     *  the motor closest to North.
     */
    public static void setMotorSpeeds(double[] speeds) {
	String taskString = "GUID:VECTOR";
	for (int i=0; i<4; i++) {
	    taskString += ":" + speeds[i];
	}
	DataReceiver.sendToDefault(navGoToManual);
	DataReceiver.sendToDefault(taskString);
    }

    /**
     *  Send PID tuning values to the helicopter.
     *
     *  @param motor The index of the motor whose loop needs tuning.
     *  @param parameter The parameter (0 for P, 1 for I, 2 for D) to tune.
     *  @param value The new value for the parameter.
     */
    public static void tunePid(int motor, int parameter, double value) {
	String taskString = "GUID:PID:SET:" + motor + ":" + parameter + ":" + value;
	DataReceiver.sendToDefault(taskString);
    }

    /**
     *  Ask the chopper to send us the current PID tuning parameters.
     */
    public static void requestPidValues() {
	DataReceiver.sendToDefault("GUID:PID:GET");
    }

    /**
     *  Send a navigation task to the chopper.
     *
     *  @param task The task to issue.
     */
    public static void makeItSo(NavTask task) {
	DataReceiver.sendToDefault(navGoToAutomatic);
	DataReceiver.sendToDefault(task.toString());
	Debug.log("Sent new navigation task: " + task.toString());
    }

    private static double[] velocityFor(int i, boolean positive) {
	double[] v = new double[]{0, 0, 0};
	v[i] = positive ? VELOCITY : -VELOCITY;
	return v;
    }

    public static void engage() {
	manualVelocity(velocityFor(0, true), bearing); 
    }

    public static void engageReverse() {
	manualVelocity(velocityFor(0, false), bearing); 
    }

    public static void toPort() {
	manualVelocity(velocityFor(1, false), bearing);
    }

    public static void toStarboard() {
	manualVelocity(velocityFor(1, true), bearing);
    }

    public static void ascend() {
	manualVelocity(velocityFor(2, true), bearing);
    }

    public static void descend() {
	manualVelocity(velocityFor(2, false), bearing);
    }

    public static void fullStop() {
	manualVelocity(new double[]{0, 0, 0}, bearing);
    }

    public static void setCourseFor(double theta) {
	manualVelocity(new double[]{0, 0, 0}, theta);
    }
}