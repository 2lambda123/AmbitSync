package idv.markkuo.ambitsync;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.locks.ReentrantLock;

import idv.markkuo.ambitlog.AmbitRecord;
import idv.markkuo.ambitlog.LogEntry;
import idv.markkuo.ambitlog.LogHeader;

public class MainActivity extends Activity {
    private static String TAG = "AmbitSync";

    //for USB permission and access
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    private PendingIntent mPermissionIntent;
    private UsbManager usbManager;
    private HashMap<Integer, Integer> connectedDevices = new HashMap<Integer, Integer>(); /* device ID: file descriptor */

    /* UI widgets handles */
    private TextView mBatteryText;
    private TextView mAmbitStatusText;
    private ProgressBar mBatteryProgress;

    private TextView mLogCountText;
    private TextView mOutputPathText;
    private ListView mEntryListView;
    private TextView mInfoText;

    // for battery updater thread
    private Runnable batteryUpdater;
    private Handler uiUpdaterHandler;
    private int batteryPercentage = 0;

    // the ListView adapter to display "Moves"
    private BaseAdapter entryAdapter;

    /* VID PID for Suunto Ambit watches */
    private static int VID;
    private static int PID[];

    /* C pointer which holds ambit device in native world, do not manipulate it in Java! */
    private long ambit_device = 0;

    /*
     * structure for storing all Ambit moves/logs. Static because at any given point of time
     * only one Ambit device can be connected. Also making it static will prevent the system
     * from deleting it upon orientation/config change, which is very expensive in this app
     */
    private static AmbitRecord record = new AmbitRecord();
    // the move list for listview adapter
    private static ArrayList<LogEntry> moveList = new ArrayList<LogEntry>();
    private int record_size = 0;

    // GPX file output directory
    private File gpxDir = null;

    // for ambit_device synchronization (calling JNI libambit)
    private ReentrantLock lock;

    // for storing/saving objects to app storage, not used now just reserved for future use
    //private SharedPreferences mPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        uiUpdaterHandler = new Handler();
        lock = new ReentrantLock();
        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        //mPrefs = getPreferences(MODE_PRIVATE);

        // get supported ambit VID/PID from resources
        VID = getResources().getInteger(R.integer.vid);
        PID = getResources().getIntArray(R.array.pid);

        // restore from saved state
        if (savedInstanceState != null) {
            Log.d(TAG, "restoring from saved state");
            ambit_device = savedInstanceState.getLong("ambit_device");
            connectedDevices = (HashMap<Integer, Integer>) savedInstanceState.getSerializable("connected_devices");
            batteryPercentage = savedInstanceState.getInt("bat_percent");
            if (connectedDevices.size() > 0 && isAmbitDisconnected()) {
                Log.d(TAG, "ambit device already disconnected");
                ambit_device = 0;
                connectedDevices.clear();
            }
        } else {
            ambit_device = 0;
        }

        setContentView(R.layout.activity_main);

        // UI widget references
        mBatteryProgress = (ProgressBar) findViewById(R.id.batteryProgressBar);
        mBatteryText = (TextView) findViewById(R.id.batteryTextView);
        mAmbitStatusText = (TextView) findViewById(R.id.ambitStatusTextView);
        mLogCountText = (TextView) findViewById(R.id.LogCountTextView);
        mOutputPathText = (TextView) findViewById(R.id.gpxOutputPathText);
        mEntryListView = (ListView)findViewById(R.id.listView);
        mInfoText = (TextView) findViewById(R.id.infoText);

        // the main ListView initialization
        entryAdapter = new MoveListAdapter(getApplicationContext(), moveList);
        mEntryListView.setAdapter(entryAdapter);

        // used for restore UI's state (visibility and text)
        if (savedInstanceState != null && ambit_device != 0) {
            mInfoText.setVisibility(savedInstanceState.getInt("info_text_vis"));
            mEntryListView.setVisibility(savedInstanceState.getInt("listview_vis"));
            mBatteryProgress.setVisibility(savedInstanceState.getInt("bat_progress_vis"));
            mBatteryText.setVisibility(savedInstanceState.getInt("bat_vis"));
            mBatteryText.setText(savedInstanceState.getString("bat_text"));

            mAmbitStatusText.setText(savedInstanceState.getString("status_text"));
            mLogCountText.setVisibility(savedInstanceState.getInt("log_count_vis"));
            mLogCountText.setText(savedInstanceState.getString("log_count_text"));
            mOutputPathText.setVisibility(savedInstanceState.getInt("output_path_vis"));
        }

        // getting USB permission and register Intent for USB device attach/detach events
        registerReceiver(usbManagerBroadcastReceiver, new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED));
        registerReceiver(usbManagerBroadcastReceiver, new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));
        registerReceiver(usbManagerBroadcastReceiver, new IntentFilter(ACTION_USB_PERMISSION));
        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);

        checkGPXOutputLocation();

        // Initial check for connected USB devices, set to fire in 1 sec
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                checkForDevices();
            }
        }, 1000);

        // setup Listview long click function
        mEntryListView.setLongClickable(true);
        mEntryListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> adapter, View view, int position, long l) {
                final LogEntry e = (LogEntry)adapter.getItemAtPosition(position);
                Log.d(TAG, "User long click on:" + e.toString());

                //ignore downloaded moves
                if (e.isDownloaded())
                    return false;

                if (ambit_device == 0) {
                    showToast("Ambit device not connected!", Toast.LENGTH_SHORT);
                    return false;
                }

                // now we can start downloading move...
                showToast("Downloading Move:" + e.toString(), Toast.LENGTH_LONG);
                uiUpdaterHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        e.markForSync(true);
                        new LogAsyncTask().execute(e);
                    }
                });

                return true;
            }
        });

        // setup Listview click function
        mEntryListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapter, View view, int position, long l) {
                final LogEntry e = (LogEntry)adapter.getItemAtPosition(position);
                final LogHeader h = e.getHeader();

                Log.d(TAG, "User click on:" + e.toString());

                if (ambit_device != 0 && !e.isDownloaded())
                    showToast("Long press to download Move", Toast.LENGTH_SHORT);

                // start another activity to show the details
                Intent intent = new Intent(MainActivity.this, MoveInfoActivity.class);

                intent.putExtra("moveDownloaded", e.isDownloaded());
                intent.putExtra("moveDateTime", h.getMoveTime());
                intent.putExtra("moveDuration", h.getMoveDuration());
                intent.putExtra("moveAscent", h.getMoveAscent());
                intent.putExtra("moveDescent", h.getMoveDescent());
                intent.putExtra("moveAscentTime", h.getMoveAscentTime());
                intent.putExtra("moveDescentTime", h.getMoveDescentTime());
                intent.putExtra("moveRecoveryTime", h.getMoveRecoveryTime());
                intent.putExtra("moveSpeed", h.getMoveSpeed());
                intent.putExtra("moveSpeedMax", h.getMoveSpeedMax());
                intent.putExtra("moveAltMax", h.getMoveAltMax());
                intent.putExtra("moveAltMin", h.getMoveAltMin());
                intent.putExtra("moveHR", h.getMoveHR());
                intent.putExtra("moveHRRange", h.getMoveHRRange());
                intent.putExtra("movePTE", h.getMovePTE());
                intent.putExtra("moveType", h.getMoveType());
                intent.putExtra("moveTemp", h.getMoveTemp());
                intent.putExtra("moveDistance", h.getMoveDistance());
                intent.putExtra("moveCalories", h.getMoveCalories());
                intent.putExtra("moveCadenceMax", h.getMoveCadenceMax());
                intent.putExtra("moveCadence", h.getMoveCadence());
                intent.putExtra("gpxDir", gpxDir);
                intent.putExtra("moveFileName", e.getFilename("gpx"));
                intent.putExtra("moveTypeInt", h.getMoveTypeInt());

                startActivity(intent);
            }
        });

        // finally we setup a battery query operation every 10 sec in a background thread
        // Note that it's not started at this point
        batteryUpdater = new Runnable() {
            @Override
            public void run() {
                batteryPercentage = 0;
                while (batteryPercentage < 100 && ambit_device != 0) {
                    if (lock.tryLock()) {
                        try {
                            if (ambit_device != 0)
                                batteryPercentage = getBatteryPercent(ambit_device);
                        } finally {
                            lock.unlock();
                        }
                    }

                    // Update the progress bar
                    uiUpdaterHandler.post(new Runnable() {
                        public void run() {
                            mBatteryProgress.setProgress(batteryPercentage);
                            mBatteryText.setText(getString(R.string.bat) + " " + batteryPercentage + "%");
                        }
                    });

                    // Update every 10 sec
                    try {
                        Thread.sleep(10 * 1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                Log.d(TAG, "Exit battery update thread");
            }
        };
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.v(TAG, "onSaveInstanceState");

        outState.putLong("ambit_device", ambit_device);
        outState.putSerializable("connected_devices", connectedDevices);
        outState.putInt("bat_percent", batteryPercentage);

        // saving item visibility and text content
        outState.putInt("info_text_vis", mInfoText.getVisibility());
        outState.putInt("listview_vis", mEntryListView.getVisibility());
        outState.putInt("bat_progress_vis", mBatteryProgress.getVisibility());
        outState.putInt("bat_vis", mBatteryText.getVisibility());
        outState.putString("bat_text", mBatteryText.getText().toString());

        outState.putString("status_text", mAmbitStatusText.getText().toString());
        outState.putInt("log_count_vis", mLogCountText.getVisibility());
        outState.putString("log_count_text", mLogCountText.getText().toString());
        outState.putInt("output_path_vis", mOutputPathText.getVisibility());
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.v(TAG, "onPause");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.v(TAG, "onResume");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.v(TAG, "onStop");
        if (record.getEntries().size() > 0 && record.getEntries().size() != record_size) {
            record_size = record.getEntries().size();

            //saving "only" log headers to filesystem
            record.clearEntrySamples();
            try {
                FileOutputStream fos = openFileOutput("ambit_move_headers", Context.MODE_PRIVATE);
                ObjectOutputStream os = new ObjectOutputStream(fos);
                os.writeObject(record.getEntries());
                os.close();
                fos.close();
                Log.d(TAG, "saving log header done. Total " + record_size + " entries saved");
            } catch (Exception e) {
                Log.w(TAG, "saving log header exception:" + e);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.v(TAG, "onStart");
        //reading log headers from filesystem, if any
        if (record.getEntries().size() == 0) {
            try {
                FileInputStream fis = openFileInput("ambit_move_headers");
                ObjectInputStream is = new ObjectInputStream(fis);
                record.setEntries((ArrayList<LogEntry>) is.readObject());
                record_size = record.getEntries().size();
                moveList.clear();
                moveList.addAll(record.getEntries());
                entryAdapter.notifyDataSetChanged();
                is.close();
                fis.close();
                Log.d(TAG, "reading log header done. Total " + record.getEntries().size() + " entries read");
            } catch (FileNotFoundException e) {
                Log.i(TAG, "no saved log. Connect Ambit device to sync for the first time");
            } catch (Exception e) {
                Log.w(TAG, "reading log header exception:" + e);
            }
        }

        if (record.getEntries().size() > 0) {
            mInfoText.setVisibility(View.INVISIBLE);
            mEntryListView.setVisibility(View.VISIBLE);

            //refresh log download status from reading external storage
            for (LogEntry e: record.getEntries()) {
                //load downloaded from File
                if (gpxDir != null) {
                    File file = new File(gpxDir, e.getFilename("gpx"));
                    if (file.exists())
                        e.setDownloaded(true);
                    else
                        e.setDownloaded(false);
                }
            }
            entryAdapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        Log.v(TAG, "onRestart");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.v(TAG, "onDestroy");
        unregisterReceiver(usbManagerBroadcastReceiver);

        // when orientation changes, this Activity will be destroyed and recreated. We don't want to
        // disconnect ambit device if it is going through orientation change, but we _DO_ want
        // to disconnect it if we are closing the app
        if (ambit_device != 0) {
            Log.d(TAG, "app is finishing:" + isFinishing());
            if (isFinishing()) {
                //disconnect ambit if app is ready closing
                lock.lock();
                notifyDeviceDetached(ambit_device);
                ambit_device = 0;
                lock.unlock();
                connectedDevices.clear();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void checkGPXOutputLocation() {
        // initialize and check external storage status and update UI to show state if necessary
        File root = getApplicationContext().getExternalFilesDir(null);
        Log.d(TAG, "External file system root: " + root);

        gpxDir = new File (root.getAbsolutePath() + "/" + getString(R.string.folder_name));
        try {
            if (!gpxDir.exists())
                gpxDir.mkdirs();
            if (!gpxDir.canWrite()) {
                Log.w(TAG, "Can't write to storage path:" + gpxDir.getAbsolutePath());
                mOutputPathText.setText(getString(R.string.ext_no_permission));
            } else {
                Log.d(TAG, "GPX Saving to" + gpxDir.getAbsolutePath());
                mOutputPathText.setText(getString(R.string.saving_to) + gpxDir.getAbsolutePath());
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Folder creation failure:" + e);
            mOutputPathText.setText(getString(R.string.ext_error));
        }
    }

    private void showToast(final String msg, final int length) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), msg, length).show();
            }
        });
    }

    // the task used for syncing all log(move) headers
    private class LogHeaderAsyncTask extends AsyncTask<Void, Integer, ArrayList<LogEntry>> {
        private int sync_ret;

        @SuppressLint("SourceLockedOrientationActivity")
        @Override
        protected void onPreExecute() {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            int currentOrientation = getResources().getConfiguration().orientation;
            if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE)
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            else
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            mAmbitStatusText.setText(getString(R.string.sync_header_status));
            record.setSyncProgress(0, 0, 0);
            record.clearEntries();
            // another progress update thread which calls on publishProgress()
            new Thread() {
                int progress;
                public void run() {
                    try {
                        while (true) {
                            progress = record.getCurrentSyncProgress();
                            if (progress != 0 && progress < 100) {
                                publishProgress(progress);
                            }
                            else if (progress == 100) {
                                publishProgress(progress);
                                break;
                            }
                            sleep(500);
                        }

                    } catch(Exception e) {
                        Log.e(TAG, "progress updater thread:" + e.getMessage());
                    }
                }
            }.start();
        }

        @Override
        protected ArrayList<LogEntry> doInBackground(Void... v) {
            lock.lock();
            //sync log header from device, save to record
            publishProgress(0);
            sync_ret = syncHeader(ambit_device, record);
            lock.unlock();

            if (sync_ret == -1) {
                //sync error
                showToast("Failed to sync Move Headers!", Toast.LENGTH_LONG);
                Log.w(TAG, "Sync move headers return:" + sync_ret);
                return record.getEntries();
            }

            //refresh downloaded states from filesystem
            for (LogEntry e: record.getEntries()) {
                if (gpxDir != null) {
                    File file = new File(gpxDir, e.getFilename("gpx"));
                    if (file.exists())
                        e.setDownloaded(true);
                }
            }

            //return results
            return record.getEntries();
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            mBatteryProgress.setProgress(progress[0]);
            //TODO: maybe we can reuse the mBatteryText to output some text progress update?
        }

        @Override
        protected void onPostExecute(ArrayList<LogEntry> data) {
            Log.d(TAG, "log header async task done! Total " + data.size() + " moves");
            moveList.clear();
            moveList.addAll(record.getEntries());
            entryAdapter.notifyDataSetChanged();
            mAmbitStatusText.setText(getString(R.string.connect_status));
            mBatteryProgress.setProgress(batteryPercentage);

            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // the task used for syncing a specific log
    private class LogAsyncTask extends AsyncTask<LogEntry, Integer, LogEntry> {
        private int sync_ret;

        @SuppressLint("SourceLockedOrientationActivity")
        @Override
        protected void onPreExecute() {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            int currentOrientation = getResources().getConfiguration().orientation;
            if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE)
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            else
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            mAmbitStatusText.setText(getString(R.string.sync_move_status));
            Log.d(TAG,"Syncing activity... ");
            record.setSyncProgress(0, 0, 0);

            // another progress update thread which calls on publishProgress()
            new Thread() {
                int progress;
                public void run() {

                    try {
                        while (true) {
                            progress = record.getCurrentSyncProgress();
                            if (progress != 0 && progress < 100) {
                                publishProgress(progress);
                            }
                            else if (progress == 100) {
                                publishProgress(progress);
                                break;
                            }
                            sleep(500);
                        }

                    } catch(Exception e) {
                        Log.e(TAG, "progress updater thread:" + e.getMessage());
                    }
                }
            }.start();
        }

        @Override
        protected LogEntry doInBackground(LogEntry... e) {
            lock.lock();
            //sync specific log from device, save to record
            sync_ret = startSync(ambit_device, record);
            lock.unlock();

            Log.d(TAG, "Syncing activity returns:" + sync_ret);
            return e[0];
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            mBatteryProgress.setProgress(progress[0]);
        }

        @Override
        protected void onPostExecute(LogEntry log) {
            //restore to original battery percent
            mBatteryProgress.setProgress(batteryPercentage);

            Log.d(TAG, "log download finished (ret:" + sync_ret + "):" + log.toString());
            mAmbitStatusText.setText(getString(R.string.connect_status));

            // clear the flag to sync
            log.markForSync(false);
            if (sync_ret == -1) {
                //sync error
                showToast("Failed to sync Move!", Toast.LENGTH_LONG);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                return;
            }

            // set the downloaded flag
            log.setDownloaded(true);

            // start writing GPX file for this log
            if (gpxDir != null) {
                File file = new File(gpxDir, log.getFilename("gpx"));
                if (file.exists()) {
                    Log.w(TAG, "gpx file already exists:" + file.getAbsolutePath());
                    showToast("gpx file already exists:" + file.getAbsolutePath(), Toast.LENGTH_LONG);
                }
                if (!log.writeGPX(file)) {
                    Log.w(TAG, "Failed to write to GPX file");
                    showToast("Failed to write GPX file", Toast.LENGTH_LONG);
                    log.setDownloaded(false);
                } else {
                    // successful case
                    showToast("Move downloaded successfully!", Toast.LENGTH_LONG);
                }
            }

            // mark memory for GC (in case the move is huge!)
            log.clear();

            // update Listview UI
            entryAdapter.notifyDataSetChanged();

            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // the Listview adapter class
    class MoveListAdapter extends BaseAdapter {

        private ArrayList<LogEntry> items;
        private Context context;
        private LayoutInflater inflater;


        private class ViewHolder {
            TextView titleTextView;
            TextView subtitleTextView;
            TextView typeTextView;
            TextView detailTextView;

            public ViewHolder(View view) {
                titleTextView = (TextView) view.findViewById(R.id.move_list_title);
                subtitleTextView = (TextView) view.findViewById(R.id.move_list_subtitle);
                typeTextView = (TextView) view.findViewById(R.id.move_list_type);
                detailTextView = (TextView) view.findViewById(R.id.move_list_detail);
            }
        }

        public MoveListAdapter(Context context, ArrayList<LogEntry> items) {
            this.context = context;
            this.items = items;
            inflater = (LayoutInflater) context
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View view, ViewGroup parent) {
            ViewHolder vh;

            if (view == null) {
                view = getLayoutInflater().inflate(R.layout.move_list_item_layout, parent, false);
                vh = new ViewHolder(view);
                view.setTag(vh);
            } else {
                vh = (ViewHolder) view.getTag();
            }

            LogEntry e = (LogEntry) getItem(i);
            LogHeader h = e.getHeader();

            vh.typeTextView.setText(h.getMoveType());
            vh.titleTextView.setText(h.getMoveTime());
            vh.subtitleTextView.setText(h.getMoveDetail());
            vh.detailTextView.setText(h.getMoveDuration());

            if (e.isDownloaded()) {
                view.setBackgroundColor(Color.parseColor("#ccffcc")); //light green
                vh.typeTextView.setText(h.getMoveType() + "\n✔"); //add a check symbol below Move Type
            } else
                view.setBackgroundColor(Color.parseColor("#ffffff")); //set white bg

            return view;
        }
    }

    // for receiving system's USB related intents, where we initialize our Ambit watch through libambit
    private final BroadcastReceiver usbManagerBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();
                Log.d(TAG, "INTENT ACTION: " + action);

                if (ACTION_USB_PERMISSION.equals(action)) {
                    Log.d(TAG, "ACTION_USB_PERMISSION");

                    synchronized (this) {
                        //we should exit and stop continuing because this may be due to orientation change
                        // and it's not necessary to re-initialize
                        if (ambit_device != 0)
                            return;

                        UsbDeviceConnection connection;
                        UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            if(device != null) {
                                connection = usbManager.openDevice(device);
                                int fd = connection.getFileDescriptor();
                                Log.d(TAG,"Ambit device fd:" + fd + " (" + device.getVendorId() + "/" +
                                        device.getProductId() + ")");
                                lock.lock();
                                ambit_device = notifyDeviceAttached(device.getVendorId(), device.getProductId(),
                                        fd, device.getDeviceName());
                                lock.unlock();

                                if (ambit_device != 0) {
                                    connectedDevices.put(device.getDeviceId(), connection.getFileDescriptor());
                                    showToast("Ambit Device Attached", Toast.LENGTH_SHORT);
                                    setAmbitUIState(true);

                                    // start battery updater
                                    new Thread(batteryUpdater).start();

                                    // set log count text on UI
                                    new Handler().post(new Runnable() {
                                        @Override
                                        public void run() {
                                            final int count;
                                            lock.lock();
                                            count = getEntryCount(ambit_device);
                                            lock.unlock();
                                            showToast("Syncing Header now...", Toast.LENGTH_LONG);
                                            uiUpdaterHandler.post(new Runnable() {
                                                @Override
                                                public void run() {
                                                    mLogCountText.setVisibility(View.VISIBLE);
                                                    mLogCountText.setText(Integer.toString(count) + " " + getString(R.string.logcount));
                                                    new LogHeaderAsyncTask().execute();
                                                }
                                            });
                                        }
                                    });

                                } else {
                                    showToast("Error initialize Ambit on this device", Toast.LENGTH_LONG);
                                    uiUpdaterHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            mAmbitStatusText.setText(getString(R.string.connect_error));
                                        }
                                    });
                                }
                            }
                        } else {
                            Log.w(TAG, "permission denied for device " + device);
                        }
                    }
                }

                if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    Log.d(TAG, "ACTION_USB_DEVICE_ATTACHED");

                    synchronized(this) {
                        UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                        if (isAmbitDevice(device))
                            usbManager.requestPermission(device, mPermissionIntent);
                        else
                            Log.d(TAG, "not an Ambit device, ignore...");
                    }
                }

                if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    Log.d(TAG, "ACTION_USB_DEVICE_DETACHED");

                    synchronized(this) {
                        UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        Log.v(TAG, "the detached device id:" + device.getDeviceId());
                        if (connectedDevices.containsKey(device.getDeviceId())) {
                            lock.lock();
                            notifyDeviceDetached(ambit_device);
                            ambit_device = 0;
                            lock.unlock();
                            connectedDevices.remove(device.getDeviceId());
                            showToast("Ambit Device Removed", Toast.LENGTH_LONG);
                            //UI updates
                            setAmbitUIState(false);
                        }
                    }
                }
            } catch(Exception e) {
                Log.d(TAG, "Exception: " + e);
            }
        }
    };

    private void setAmbitUIState(final boolean ambit_connected) {
        batteryPercentage = 0;
        uiUpdaterHandler.post(new Runnable() {
            @Override
            public void run() {
                if (ambit_connected) {
                    // when ambit device is attached and granted for permission
                    mAmbitStatusText.setText(getString(R.string.connect_status));
                    mInfoText.setVisibility(View.INVISIBLE);//will not bring it up ever
                    mEntryListView.setVisibility(View.VISIBLE);
                    mBatteryProgress.setVisibility(View.VISIBLE);
                    mBatteryText.setVisibility(View.VISIBLE);
                    mOutputPathText.setVisibility(View.VISIBLE);
                } else {
                    // when ambit device is detached
                    mBatteryProgress.setProgress(batteryPercentage);
                    mBatteryProgress.setVisibility(View.INVISIBLE);
                    mBatteryText.setText("");
                    mBatteryText.setVisibility(View.INVISIBLE);
                    mLogCountText.setText("");
                    mLogCountText.setVisibility(View.INVISIBLE);
                    mOutputPathText.setVisibility(View.INVISIBLE);
                    mAmbitStatusText.setText(getString(R.string.disconnect_status));
                }
            }
        });
    }

    // check if the USB device's VID/PID is among all ambit devices
    private boolean isAmbitDevice(UsbDevice device) {
        if (device == null)
            return false;

        if (device.getVendorId() == VID)
            for (int pid: PID)
                if (device.getProductId() == pid)
                    return true;

        return false;
    }

    // check for newly connected Ambit device on the USB
    private void checkForDevices() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        Iterator<UsbDevice> deviceIterator = deviceList.values().iterator();

        Log.d(TAG, "check for ambit device...");
        while(deviceIterator.hasNext()) {
            UsbDevice device = deviceIterator.next();
            if (isAmbitDevice(device)) {
                usbManager.requestPermission(device, mPermissionIntent);
                break;
            }
        }
    }

    private boolean isAmbitDisconnected() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        Iterator<UsbDevice> deviceIterator = deviceList.values().iterator();

        while(deviceIterator.hasNext()) {
            UsbDevice device = deviceIterator.next();
            if (connectedDevices.containsKey(device.getDeviceId()))
                return false;
        }
        return true;
    }

    /* reserved for future use
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
    */

    // native APIs
    private static native long notifyDeviceAttached(int vid, int pid, int fd, String path);
    private static native void notifyDeviceDetached(long device);
    private static native int getBatteryPercent(long device);
    private static native int getEntryCount(long device);
    private static native int syncHeader(long device, AmbitRecord record);
    private static native int startSync(long device, AmbitRecord record);
    private static native void stopSync(long device); // not implemented yet
    private static native void nativeInit();


    // loading native libraries
    static {
        System.loadLibrary("iconv");
        System.loadLibrary("usb-android");
        System.loadLibrary("ambit");
        System.loadLibrary("ambitsync");
        nativeInit();
    }
}
