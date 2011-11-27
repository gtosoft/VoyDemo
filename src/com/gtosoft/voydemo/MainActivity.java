package com.gtosoft.voydemo;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.widget.TextView;

import com.artfulbits.aiCharts.ChartView;
import com.artfulbits.aiCharts.Base.ChartAxis.LabelsMode;
import com.artfulbits.aiCharts.Base.ChartPoint;
import com.artfulbits.aiCharts.Base.ChartPointCollection;
import com.artfulbits.aiCharts.Base.ChartSeries;
import com.gtosoft.libvoyager.android.ActivityHelper;
import com.gtosoft.libvoyager.db.DashDB;
import com.gtosoft.libvoyager.session.HybridSession;
import com.gtosoft.libvoyager.session.OBD2Session;
import com.gtosoft.libvoyager.util.EasyTime;
import com.gtosoft.libvoyager.util.EventCallback;
import com.gtosoft.libvoyager.util.GeneralStats;
import com.gtosoft.libvoyager.util.OOBMessageTypes;
import com.gtosoft.libvoyager.view.MyViewFlipper;

public class MainActivity extends Activity {
	MyViewFlipper mvFlipper;

	ChartView mcvBig;

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

	// Hybridsession is a class that lets us communicate using OBD or passive or
	// direct network commands. It manages various "session" classes to do this.
	HybridSession hs;

	// ActivityHelper helps us perform Bluetooth discovery so we don't have to
	// write a ton of code.
	ActivityHelper aHelper = null;

	String mBTPeerAddr = "";

	// ChartAdapter chartAdapter;
	// List<ChartView> chartArray = new ArrayList<ChartView>();

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
//		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
		
		setContentView(R.layout.flippages);

		// assign views
		tvMessages = (TextView) findViewById(R.id.p01tv1);
		mvFlipper = (MyViewFlipper) findViewById(R.id.vFlipper);
		mcvBig = (ChartView) findViewById(R.id.p02cv1);

		setupCharts();

		aHelper = new ActivityHelper(this);
		// Register with libvoyager to receive "we found an ELM device nearby"
		// message so when we perform a discovery, this method gets called.
		aHelper.registerChosenDeviceCallback(chosenCallback);
		// in the onResume method we will make the call to ActivityHelper to
		// actually kick off the bluetooth discovery.

		// chartAdapter = new ChartAdapter(this, R.layout.chartlistitem,
		// chartArray);

		// ListView lv = (ListView) findViewById(R.id.lvCharts);
		// lv.setAdapter(chartAdapter);
		if (DEBUG)
			msg("Startup DONE");
	}

	@Override
	protected void onResume() {
		super.onResume();

		doBestAvailable();

	}

	private boolean doBestAvailable() {
		// kick off Bluetooth discovery. At this point the phone looks for
		// nearby bluetooth devices.
		// For each device, ActivityHelper checks its name to see if it's an OBD
		// device.
		// If the device seems to be an ELM OBD device, it calls the
		// "chosenCallback" event with details about the device (its MAC).
		if (hs == null || hs.getEBT().isConnected() != true) {
			msg("Performing bluetooth discovery...");
			connectToBestAvailable();
		} else {
			msg("Unable to perform bluetooth discovery. BT Connected = "
					+ hs.getEBT().isConnected());
			return false;
		}

		return true;
	}

	/**
	 * Either connects to last known device, or performs a discovery to find
	 * nearby OBD devices.
	 */
	private void connectToBestAvailable() {
		String lastmac = aHelper.getLastUsedMAC();

		// Connect to last known device if possible. Otherwise do the usual
		// bluetooth scan.
		if (lastmac.length() == 17) {
			if (DEBUG)
				msg("Using last known device MAC!");
			// if we setupSession ourself, we are skipping the activityhelper
			// conveniences of discovery/etc.
			setupSession(lastmac);
		} else {
			if (DEBUG)
				msg("No preferred device has been selected. Scanning...");
			// let the activityhelper find the device and fire off the
			// "chosenCallback" when it finds one.
			aHelper.startDiscovering();
		}
	}

	/**
	 * libVoyager can do the BT discovery and device choosing for you. When it
	 * finds/chooses a device it runs the device chosen callback. This method
	 * defines what to do when a new device is found.
	 */
	EventCallback chosenCallback = new EventCallback() {

		@Override
		public void onELMDeviceChosen(String MAC) {
			mBTPeerAddr = MAC;
			msg("Device found/chosen: " + MAC);
			setupSession(MAC);
		}

	};

	/**
	 * This method gets called by the broadcast receiver, for bluetooth devices
	 * which are "OBD" devices. This takes care of any necessary actions to open
	 * a connection to the specified device. Run synchronized in case the
	 * discovery process throws us multiple devices. We only want the first
	 * valid one.
	 * 
	 * @param deviceMACAddress
	 * @return - true on success, false otherwise.
	 */
	private synchronized boolean setupSession(String deviceMACAddress) {
		// If there's an existing hybrid session, shut it down.
		if (hs != null) {
			hs.shutdown();
		}

		// instantiate dashDB if necessary.
		if (ddb == null) {
			msg("Spinning up DashDB...");
			ddb = new DashDB(MainActivity.this);
			msg("DashDB Ready.");
		}

		msg("Setting up hybridSession. It will now establish a bluetooth connection.");

		aHelper.setLastUsedMAC(deviceMACAddress);

		// instantiate hybridsession, which is just a class that controls
		// subclasses such as Monitorsession and OBDSession, that communicate
		// with the network in different ways.
		hs = new HybridSession(BluetoothAdapter.getDefaultAdapter(),
				deviceMACAddress, ddb, mLocalecbOOBMessageHandler);
		// after hybridsession is successful at opening the bluetooth
		// connection, we will get an OOB notification that the IO state changed
		// to "1".

		// Sets the session type to OBD2. nothing fancy.
		// hs.setActiveSession(HybridSession.SESSION_TYPE_OBD2);

		// register a method to be called when new data arrives.
		hs.registerDPArrivedCallback(mLocalDPNArrivedHandler);

		mBTPeerAddr = deviceMACAddress;

		return true;
	}

	int mLastIOState = 1234;

	private void ioStateChanged(int newState) {

		// Avoid non-events where iostateChanged is fired but no state change
		// actually occurred.
		if (newState == mLastIOState) {
			return;
		} else {
			mLastIOState = newState;
		}

		// Did bluetooth just establish connection? If so then kick off a
		// session detection to see what we're connected to.
		if (newState == 1) {
			// Bluetooth just connected, so kick off a thread that does network
			// detection and prepares the hybridsession class for use.
			String peername = getStats().getStat("hs.ebt.peerName");
			String peermac = getStats().getStat("hs.ebt.peerMAC");
			msg("Detecting capabilities of device " + peermac + "(" + peername
					+ ")");
			// msg (getStats().getAllStatsAsString());
			detectSessionInBackground();
		} else {
			// Bluetooth just disconnected. ELMBT will try to reconnect a preset
			// number of times, at a preset interval.
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
	};

	protected void onDestroy() {
		super.onDestroy();

		// give hs a chance to properly close the network/bluetooth link.
		if (hs != null)
			hs.shutdown();
	};

	/**
	 * Kicks off an asynchronous thread which does network/hardware detection
	 * via the hybridSession class.
	 */
	private void detectSessionInBackground() {
		new Thread() {
			public void run() {
				msg("Starting asynchronous session detection...");
				mgStats.incrementStat("netDetectAttempts"); // sets it to
															// initial value, 1.

				// loop until either 1. we detect the network, or 2. bluetooth
				// disconnects.
				// Typically this detection process takes 5-15 seconds depending
				// on type of network. Cacheing optimizations haven't been built
				// in yet but would be quite easy down the road.
				while (hs.runSessionDetection() != true
						&& hs.getEBT().isConnected() == true) {
					mgStats.incrementStat("netDetectAttempts");
					if (!EasyTime.safeSleep(1000))
						break;
				}

				if (hs.isDetectionValid() == true) {
					msg("Detection was successful, switching to OBD mode and adding a few datapoints to the scan...");
					// switch to OBD mode.
					hs.setActiveSession(HybridSession.SESSION_TYPE_OBD2);

					// start with a clean slate
					hs.getRoutineScan().removeAllDPNs();
					// add speed and RPM to the routinescan. Routinescan will
					// continuously request PIDs and as they are decoded, the
					// DPDecoded event will fire.
					hs.getRoutineScan().addDPN("SPEED");
					hs.getRoutineScan().addDPN("RPM");
				}

				msg("Session detection complete. result="
						+ hs.getCapabilitiesString());
			}
		}.start();
	}

	/**
	 * Define what action the hybridsession should take as it decodes data from
	 * the OBD network.
	 */
	EventCallback mLocalDPNArrivedHandler = new EventCallback() {
		@Override
		public void onDPArrived(String DPN, String sDecodedData,
				int iDecodedData) {

			if (DPN.equals("RPM")) {
				Double y = 0.0d;
				try {
					y = Double.valueOf(sDecodedData);
				} catch (NumberFormatException e) {
				}
				addPointToBigGraph(y);
			}

		}// end of onDPArrived.
	};// end of eventcallback def.

	// Defines the logic to take place when an out of band message is generated
	// by the hybrid session layer.
	EventCallback mLocalecbOOBMessageHandler = new EventCallback() {
		@Override
		public void onOOBDataArrived(String dataName, String dataValue) {
			msg("(OOB Message) " + dataName + "=" + dataValue);

			// state change?
			if (dataName.equals(OOBMessageTypes.IO_STATE_CHANGE)) {
				int newState = 0;
				try {
					newState = Integer.valueOf(dataValue);
					ioStateChanged(newState);
				} catch (Exception e) {
					msg("ERROR: Could not interpret new state as string: "
							+ dataValue + " E=" + e.getMessage());
				}
			}// end of "if this was a io state change".

			// Bluetooth unable to connect to peer?
			if (dataName.equals(OOBMessageTypes.BLUETOOTH_FAILED_CONNECT)) {

				if (dataValue.equals("0")) {
					msg("Unable to find peer. Searching for nearby OBD adapters.");
					aHelper.startDiscovering();
				}
			}

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
					msg("Just connected - adding SPEED DPN to routinescan.");

					// Add some datapoints to the "routine scan" which is an
					// automatic loop that continuously scans those PIDs.
					hs.getRoutineScan().addDPN("SPEED");
					hs.getRoutineScan().addDPN("RPM");
				} else {
					msg("Just disconnected. removing all DPNs from routinescan.");
					hs.getRoutineScan().removeAllDPNs();
				}

			}// end of session state change handler.

			// Did we just perform detection stuff?
			if (dataName.equals(OOBMessageTypes.AUTODETECT_SUMMARY)) {
				if (hs.isDetectionValid() != true)
					return;

				if (hs.isHardwareSniffable() == true) {
					msg ("Monitor is supported! Switching to monitor mode.");
					hs.setActiveSession(HybridSession.SESSION_TYPE_MONITOR);
					// at this point, monitor mode is active and it will automatically detect the network if available. 
					
				} else {
					msg ("Monitor mode not supported so we'll enable OBD2 scanning.");
					
					// switch to OBD2 communications mode
					hs.setActiveSession(HybridSession.SESSION_TYPE_OBD2);
					
					// sanity check
					if (hs.getRoutineScan() == null) return;

					// add one or more datapoints to the routine scan class so that it actively scans that PID to generate DPN arrived events. 
					hs.getRoutineScan().addDPN("RPM");
				}
					
					
				
			}// end of "if this is a autodetect summary"
		}// end of OOB event arrived handler function definition. 
	};// end of event handler definition. 

	/**
	 * @return - a reference to our generalstats object, which will also contain
	 *         all the stats of our children classes.
	 */
	public GeneralStats getStats() {
		// Merge stats from hybridsession and all of its children class.
		if (hs != null)
			mgStats.merge("hs", hs.getStats());

		// returns our stats object, which now contains all the stats of our
		// children classes.
		return mgStats;
	}

	/**
	 * Display a message to the user by adding it to the scrolling text view.
	 */
	private void showMsg(final String m) {
		muiHandler.post(new Runnable() {
			public void run() {
				tvMessages.append(m + "\n");
			}
		});
	}

	private void msg(String m) {
		showMsg(m);
		Log.d("Activity", m);
	}

	/**
	 * removes commas and takes just the first value, if present.
	 * 
	 * @param decodedData
	 * @return
	 */
	Double getPrimaryDPNValue(String decodedData) {
		Double Y = 0d;

		// do our best to extract the data value.
		if (decodedData.contains(",")) {
			try {
				Y = Double.valueOf(decodedData.split(",")[0]);
			} catch (NumberFormatException e) {
			}
		} else {
			try {
				Y = Double.valueOf(decodedData);
			} catch (NumberFormatException e) {
			}
		}

		return Y;
	}

	private void clearAllGraphPoints(ChartView cv) {

		try {
			ChartSeries cs = cv.getSeries().get(0);
			ChartPointCollection points = cs.getPoints();
			points.clear();

		} catch (Exception e) {
			if (DEBUG)
				msg("Error clearing chart points. E=" + e.getMessage());
		}
	}

	// private boolean doesChartExist (String title) {
	// for (ChartView c : chartArray) {
	// if (c.getTitles().get(0).getText().equals(title)) {
	// return true;
	// }
	// }
	//
	// return false;
	// }
	//
	// private ChartView addChart (String title) {
	// msg ("Adding chart with title " + title);
	// ChartView c = new ChartView(this,R.xml.shortchart);
	//
	// setXYTitles(c, "X", "Y", title);
	// clearAllGraphPoints(c);
	//
	// chartArray.add(c);
	//
	// return c;
	// }

	// /**
	// * Looks for chart with given title and returns it. Otherwise returns
	// null.
	// * @param title
	// * @return
	// */
	// private ChartView getChartByTitle (String title) {
	// for (ChartView c : chartArray) {
	// if (c.getTitles().get(0).getText().equals(title)) {
	// return c;
	// }
	// }
	//
	// // otherwise, create it!
	// return addChart(title);
	//
	// }

	private void setXYTitles(final ChartView cv, final String newXTitle,
			final String newYTitle, final String newGraphTitle) {
		muiHandler.post(new Runnable() {
			public void run() {
				cv.getSeries().get(0).getActualXAxis().setTitle(newXTitle);
				cv.getSeries().get(0).getActualYAxis().setTitle(newYTitle);
				cv.getSeries().get(0).getActualYAxis()
						.setLabelsMode(LabelsMode.RangeLabels);
				cv.getSeries().get(0).getActualYAxis().setGridVisible(true);
				cv.getSeries().get(0).getActualYAxis().setShowLabels(true);
				cv.getTitles().get(0).setText(newGraphTitle);
			}// end of run
		});// end of post
	}// end of setXYTitles

	
	
	
	private void addPointToGraph(final ChartView cv, final double X,
			final double Y) {
		final ChartPoint point = new ChartPoint(X, Y);

		// post it to the UI thread.
		muiHandler.post(new Runnable() {
			public void run() {
				// cv.getSeries().get(0).getArea().
				cv.getSeries().get(0).getPoints().add(point);

				// scroll if necessary.
				int numPoints = cv.getSeries().get(0).getPoints().size();
				// remove the leftmost point. This has the affect of
				// scrolling...
				if (numPoints > 100) {
					cv.getSeries().get(0).getPoints().removeAt(0);
					cv.getSeries().get(0).getActualXAxis().getScale()
							.setMinimum(X - 100);
					cv.getSeries().get(0).getActualXAxis().getScale()
							.setMaximum(X);
				}// end of scroll check.
			}// end of run
		}); // end of post
	}// end of addPointToGraph...

	// private void addPointToGraphSimple(final String title, final double Y) {
	//
	// if (doesChartExist(title) == false) {
	// muiHandler.post(new Runnable () {
	// public void run () {
	// addChart(title);
	// msg ("Added chart " + title + " in background.");
	// }
	// });
	// EasyTime.safeSleep(5000);
	// }
	//
	// ChartView cv = getChartByTitle(title);
	//
	// if (cv == null) {
	// msg ("Chart not found! title=" + title);
	// return;
	// }
	//
	// double X = 0;
	//
	// // see if there's already an X point, if so, add one to it.
	// try {
	// int numpoints = cv.getSeries().get(0).getPoints().size();
	// X = cv.getSeries().get(0).getPoints().get(numpoints-1).getX() + 1;
	// } catch (Exception e) {
	// msg ("Error getting last X for chart " + title);
	// // in this case we fall back on default X.
	// }
	// msg ("Adding point (" + X + "," + Y + ") to chart " + title);
	// addPointToGraph(cv, X, Y);
	//
	// muiHandler.post(new Runnable () {
	// public void run () {
	// chartAdapter.notifyDataSetChanged();
	// }
	// });
	// }

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {

		// page 01 TODO: make an wire-frame icon for each page representing what
		// that page offers.
		MenuItem itemPageOne = menu.add("Status");
		itemPageOne.setOnMenuItemClickListener(new OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				mvFlipper.setvFlipperPage(0);
				return false;
			}
		});

		// page 02 TODO: make an wire-frame icon for each page representing what
		// that page offers.
		MenuItem itemPageTwo = menu.add("Chart");
		itemPageTwo.setOnMenuItemClickListener(new OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				mvFlipper.setvFlipperPage(1);
				return false;
			}
		});

		// page 01 TODO: make an wire-frame icon for each page representing what
		// that page offers.
		MenuItem itemPageThree = menu.add("Page-3");
		itemPageThree.setOnMenuItemClickListener(new OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				mvFlipper.setvFlipperPage(2);
				return false;
			}
		});

		return super.onCreateOptionsMenu(menu);
	}

	/**
	 * Do initial set up of chart(s).
	 */
	private void setupCharts() {
		setXYTitles(mcvBig, "time", "RPM", "Engine RPM");
	}

	double mbigX = 0;

	private void addPointToBigGraph(double yValue) {
		addPointToGraph(mcvBig, mbigX++, yValue);
	}

} // end of class.