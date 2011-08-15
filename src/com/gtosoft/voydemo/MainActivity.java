package com.gtosoft.voydemo;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.TextView;

import com.gtosoft.libvoyager.android.ActivityHelper;
import com.gtosoft.libvoyager.db.DashDB;
import com.gtosoft.libvoyager.session.HybridSession;
import com.gtosoft.libvoyager.session.OBD2Session;
import com.gtosoft.libvoyager.util.EasyTime;
import com.gtosoft.libvoyager.util.EventCallback;
import com.gtosoft.libvoyager.util.GeneralStats;
import com.gtosoft.libvoyager.util.OOBMessageTypes;
// Thanks Lincoln :)
//import static com.gtosoft.libvoyager.util.OOBMessageTypes.*;


public class MainActivity extends Activity {
	// we'll use this handler to post screen related updates to the main thread. 
	Handler muiHandler = new Handler();
	
	// text view for showing messages to the user. 
	TextView tvMessages;
	
	// General Stats about the internals of the app. 
	GeneralStats mgStats = new GeneralStats();
	
	// Dash DB. Has OBD PID lookup tables and much more. see assets/schema.sql.
	DashDB ddb = null;
	
	// true if we want to see lots of messages. set to false for releases. 
	final boolean DEBUG = true;

	// Hybridsession is a class that lets us communicate using OBD or passive or direct network commands. It manages various "session" classes to do this. 
	HybridSession hs;
	
	// ActivityHelper helps us perform Bluetooth discovery so we don't have to write a ton of code. 
	ActivityHelper aHelper = null; 
	
	String mBTPeerAddr = "";
	
	/** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        tvMessages = (TextView) findViewById(R.id.tvMessages);
        
        aHelper = new ActivityHelper(this);
        // Register with libvoyager to receive "we found an ELM device nearby" message so when we perform a discovery, this method gets called.
        aHelper.registerChosenDeviceCallback(chosenCallback);
        // in the onResume method we will make the call to ActivityHelper to actually kick off the bluetooth discovery. 
    }

    @Override
    protected void onResume() {
    	super.onResume();

    	// kick off Bluetooth discovery. At this point the phone looks for nearby bluetooth devices. 
        //    For each device, ActivityHelper checks its name to see if it's an OBD device.
        //    If the device seems to be an ELM OBD device, it calls the "chosenCallback" event with details about the device (its MAC). 
        if (hs == null || hs.getEBT().isConnected() != true) {
        	msg ("Performing bluetooth discovery...");
        	aHelper.startDiscovering();
        } else {
        	msg ("Unable to perform bluetooth discovery. BT Connected = " + hs.getEBT().isConnected());
        }
    }
    
    @Override
    protected void onPause() {
    	super.onPause();
    };
    
    protected void onDestroy() {
    	super.onDestroy();
    	
    	// give hs a chance to properly close the network/bluetooth link. 
    	if (hs != null) hs.shutdown();
    };

    
    /** 
     * libVoyager can do the BT discovery and device choosing for you. When it finds/chooses a device  it runs the device chosen callback.
     * This method defines what to do when a new device is found.  
     */
    EventCallback chosenCallback = new EventCallback () {

    	@Override
    	public void onELMDeviceChosen(String MAC) {
    		mBTPeerAddr = MAC;
    		msg ("Device found/chosen: " + MAC);
    		setupSession(MAC);
    	}
    	
    };

    /**
     * This method gets called by the broadcast receiver, for bluetooth devices which are "OBD" devices. 
     * This takes care of any necessary actions to open a connection to the specified device. 
     * Run synchronized in case the discovery process throws us multiple devices. We only want the first valid one. 
     * @param deviceMACAddress
     * @return - true on success, false otherwise. 
     */
    private synchronized boolean setupSession(String deviceMACAddress) {
  	  // Make sure we aren't threading out into more than one device. we can't presently handle multiple OBD devices at once. 
  	  if (hs != null) {
  		  msg ("Multiple OBD devices detected. throwing out " + deviceMACAddress);
  		  return false;
  	  }
  	  
  	  // instantiate dashDB if necessary.
  	  if (ddb == null) {
  		  msg  ("Spinning up DashDB...");
  		  ddb = new DashDB(MainActivity.this);
  		  msg  ("DashDB Ready.");
  	  }

  	 
  	  msg ("Setting up hybridSession. It will now establish a bluetooth connection.");
  	  
  	  // instantiate hybridsession, which is just a class that controls subclasses such as Monitorsession and OBDSession, that communicate with the network in different ways.
  	  hs = new HybridSession (BluetoothAdapter.getDefaultAdapter(), deviceMACAddress, ddb, ecbOOBMessageHandler);
  	  // after hybridsession is successful at opening the bluetooth connection, we will get an OOB notification that the IO state changed to "1". 
  	  
  	  // Sets the session type to OBD2. nothing fancy.
  	  hs.setActiveSession(HybridSession.SESSION_TYPE_OBD2);
  	  
  	  // register a method to be called when new data arrives. 
  	  hs.registerDPArrivedCallback(ecbDPNArrivedHandler);

  	  mBTPeerAddr = deviceMACAddress;
        
  	  return true;
    }

    
    /**
     * Kicks off an asynchronous thread which does network/hardware detection via the hybridSession class. 
     */
    private void detectSessionInBackground () {
    	new Thread() {
    		public void run () {
    			msg ("Starting asynchronous session detection...");
				mgStats.incrementStat("netDetectAttempts"); // sets it to initial value, 1. 
				
				// loop until either 1. we detect the network, or 2. bluetooth disconnects. 
				// Typically this detection process takes 5-15 seconds depending on type of network. Cacheing optimizations haven't been built in yet but would be quite easy down the road. 
    			while (hs.runSessionDetection() != true && hs.getEBT().isConnected() == true) {
    				mgStats.incrementStat("netDetectAttempts");
    				if (!EasyTime.safeSleep(1000)) break;
    			}
    			
    			if (hs.isDetectionValid() == true) {
    				msg ("Detection was successful, switching to OBD mode and adding a few datapoints to the scan...");
    				// switch to OBD mode. 
    				hs.setActiveSession(HybridSession.SESSION_TYPE_OBD2);

    				// start with a clean slate
    				hs.getRoutineScan().removeAllDPNs();
    				// add speed and RPM to the routinescan. Routinescan will continuously request PIDs and as they are decoded, the DPDecoded event will fire. 
    				hs.getRoutineScan().addDPN("SPEED");
    				hs.getRoutineScan().addDPN("RPM");
    			}
    			
    			msg ("Session detection complete. result=" + hs.getCapabilitiesString());
    		}
    	}.start();
    }
    
    int mLastIOState = 1234;
    private void ioStateChanged (int newState) {
    	
    	// Avoid non-events where iostateChanged is fired but no state change actually occurred. 
    	if (newState == mLastIOState) {
    		return;
    	} else {
        	mLastIOState = newState;
    	}

    	
    	if (newState == 1) {
    		// Bluetooth just connected, so kick off a thread that does network detection and prepares the hybridsession class for use. 
    	  	String peername = getStats().getStat("hs.ebt.peerName");
    	  	String peermac = getStats().getStat("hs.ebt.peerMAC");
    	  	msg ("Detecting capabilities of device " + peermac + "(" + peername + ")");
//    		msg (getStats().getAllStatsAsString());
    		detectSessionInBackground();
    	} else {
    		// Bluetooth just disconnected. ELMBT will try to reconnect a preset number of times, at a preset interval. 
    	}
    }
    
    /**
     * Define what action the hybridsession should take as it decodes data from the OBD network. 
     */
	EventCallback ecbDPNArrivedHandler = new EventCallback () {
		@Override
		public void onDPArrived(String DPN, String sDecodedData, int iDecodedData) {
			
			msg ("(DP Arrived) " + DPN + "=" + sDecodedData);
			
			//			if (DPN.equals("SPEED")) {
			//				addPointToSpeedGraph(sDecodedData);
			//			}
			//
			//			if (DPN.equals("RPM")) {
			//				addPointToRPMGraph(sDecodedData);
			//			}

		}// end of onDPArrived. 
	};// end of eventcallback def. 
	  

	// Defines the logic to take place when an out of band message is generated by the hybrid session layer. 
	EventCallback ecbOOBMessageHandler = new EventCallback () {
		@Override
		public void onOOBDataArrived(String dataName, String dataValue) {
			msg ("(OOB Message) " + dataName + "=" + dataValue);
			
			// state change?
			if (dataName.equals(OOBMessageTypes.IO_STATE_CHANGE)) {
				int newState = 0;
				try {
					newState = Integer.valueOf(dataValue);
					ioStateChanged(newState);
				} catch (Exception e) {
					msg ("ERROR: Could not interpret new state as string: " + dataValue + " E=" + e.getMessage());
				}
			}// end of "if this was a io state change". 
			
			// session state change? 
			if (dataName.equals(OOBMessageTypes.SESSION_STATE_CHANGE)) {
				int newState = 0;
				
				// convert from string to integer. 
				try {
					newState = Integer.valueOf(dataValue);
				} catch (NumberFormatException e) {
					return;
				}

				// just connected? 
				if (newState >= OBD2Session.STATE_OBDCONNECTED) {
					msg ("Just connected - adding SPEED DPN to routinescan.");
					
					// Add some datapoints to the "routine scan" which is an automatic loop that continuously scans those PIDs.
					hs.getRoutineScan().addDPN("SPEED");
					hs.getRoutineScan().addDPN("RPM");
				} else {
					msg ("Just disconnected. removing all DPNs from routinescan.");
					hs.getRoutineScan().removeAllDPNs();
				}

			}// end of session state change handler. 
			
			
		}
	};

	/**
	 * @return - a reference to our generalstats object, which will also contain all the stats of our children classes. 
	 */
	public GeneralStats getStats () {
		// Merge stats from hybridsession and all of its children class. 
		if (hs != null) mgStats.merge("hs", hs.getStats());
		
		// returns our stats object, which now contains all the stats of our children classes. 
		return mgStats;
	}

	/**
	 * Display a message to the user by adding it to the scrolling text view. 
	 */
	private void showMsg (final String m) {
		muiHandler.post(new Runnable () {
			public void run () {
				tvMessages.append(m + "\n");
			}
		});
	}
	
	private void msg (String m) {
		showMsg(m);
		Log.d("Activity",m);
	}
}