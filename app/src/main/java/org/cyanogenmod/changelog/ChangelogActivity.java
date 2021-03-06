/*
 * Copyright (c) 2016 The CyanogenMod Project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.cyanogenmod.changelog;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.StreamCorruptedException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ChangelogActivity extends Activity implements SwipeRefreshLayout.OnRefreshListener {
    /**
     * Debug tag.
     */
    private static final String TAG = "ChangelogActivity";

    /**
     * Content view.
     */
    private SwipeRefreshLayout mSwipeRefreshLayout;

    /**
     * RecyclerView used to list all the changes.
     */
    private RecyclerView mRecyclerView;

    /**
     * Adapter for the RecyclerView.
     */
    private ChangelogAdapter mAdapter;

    /**
     * Dialog showing info about the device.
     */
    private Dialog mInfoDialog;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        /* Setup and create Views */
        init();
        /* Populate RecyclerView with cached data */
        bindCache();
        /* Fetch data */
        updateChangelog();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.actions, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_device_info:
                mInfoDialog.show();
                break;
            case R.id.menu_refresh:
                if (!mSwipeRefreshLayout.isRefreshing()) updateChangelog();
                break;
        }
        return true;
    }

    @Override
    public void onRefresh() {
        updateChangelog();
    }

    /**
     * Utility method.
     */
    private void init() {
        // Setup SwipeRefreshLayout
        mSwipeRefreshLayout = (SwipeRefreshLayout) findViewById(R.id.swipe_refresh);
        // Setup refresh listener which triggers new data loading
        mSwipeRefreshLayout.setOnRefreshListener(this);
        // Color scheme of the refresh spinner
        mSwipeRefreshLayout.setColorSchemeResources(
                R.color.color_primary_dark, R.color.color_accent);
        // Setup RecyclerView
        mRecyclerView = (RecyclerView) findViewById(R.id.recycler_view);
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        // Setup divider for RecyclerView items
        mRecyclerView.addItemDecoration(new Divider(this));
        // Setup item animator
        mRecyclerView.setItemAnimator(null);    // Disable to prevent view blinking when refreshing
        // Setup and initialize RecyclerView adapter
        mAdapter = new ChangelogAdapter(this, new CopyOnWriteArrayList<Change>());
        mRecyclerView.setAdapter(mAdapter);
        // Setup and initialize info dialog
        String message = String.format("%s %s\n\n%s %s\n\n%s %s",
                getString(R.string.device_info_device), Device.device,
                getString(R.string.device_info_version), Device.CMVersion,
                getString(R.string.device_info_update_channel), Device.CMReleaseChannel);
        View infoDialog = getLayoutInflater().inflate(R.layout.info_dialog, mRecyclerView, false);
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_InfoDialog)
                .setView(infoDialog)
                .setPositiveButton(R.string.dialog_ok, null);
        TextView dialogMessage = (TextView) infoDialog.findViewById(R.id.info_dialog_message);
        dialogMessage.setText(message);
        mInfoDialog = builder.create();
    }

    /**
     * Fetch data from API asynchronously.
     */
    private void updateChangelog() {
        Log.i(TAG, "Updating Changelog");

        if (!deviceIsConnected()) {
            Log.e(TAG, "Missing network connection");
            Toast.makeText(this, R.string.data_connection_required, Toast.LENGTH_SHORT).show();
            mSwipeRefreshLayout.setRefreshing(false);
            return;
        }

        new ChangelogTask().execute(80);
    }

    /**
     * Check if the device is connected to internet, return true if the device has data connection.
     *
     * @return true if device is connected to internet, otherwise returns false.
     */
    private boolean deviceIsConnected() {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();

        return !(networkInfo == null || !networkInfo.isConnected());
    }

    /**
     * Read cached data and bind it to the RecyclerView.
     */
    private void bindCache() {
        try {
            FileInputStream fileInputStream = new FileInputStream(new File(getCacheDir(), "cache"));
            ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
            List<Change> cachedData = new LinkedList<>();
            Change temp;
            while ((temp = (Change) objectInputStream.readObject()) != null) {
                cachedData.add(temp);
            }
            objectInputStream.close();
            mAdapter.clear();
            mAdapter.addAll(cachedData);
            Log.d(TAG, "Restored cache");
        } catch (FileNotFoundException e) {
            Log.w(TAG, "Cache not found.");
        } catch (EOFException e) {
            Log.e(TAG, "Error while reading cache! (EOF) ");
        } catch (StreamCorruptedException e) {
            Log.e(TAG, "Corrupted cache!");
        } catch (IOException e) {
            Log.e(TAG, "Error while reading cache!");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    private class ChangelogTask extends AsyncTask<Integer, Void, List<Change>> {
        // Runs on UI thread
        @Override
        protected void onPreExecute() {
            /* Start refreshing circle animation.
             * Wrap in runnable to workaround SwipeRefreshLayout bug.
             * View: https://code.google.com/p/android/issues/detail?id=77712
             */
            mSwipeRefreshLayout.post(new Runnable() {
                @Override
                public void run() {
                    mSwipeRefreshLayout.setRefreshing(true);
                }
            });
        }

        // Runs on the separate thread
        @Override
        protected List<Change> doInBackground(Integer... q) {
            List<Change> changes = new LinkedList<>();
            int n = 120, start = 0; // number of changes to fetch and to skip
            String branch = "(" +
                    "branch:cm-" + Device.CMNumber + "%20OR%20" +
                    "branch:cm-" + Device.CMNumber + "-caf" + "%20OR%20" +
                    "branch:cm-" + Device.CMNumber + "-caf-" + Device.board +
                    ")";
            RESTfulURI uri = new RESTfulURI(RESTfulURI.STATUS_MERGED, branch, n, start);
            while (changes.size() < q[0]) {
                long time = System.currentTimeMillis();
                uri.start = start;
                String apiUrl = uri.toString();
                try {
                    Log.d(TAG, "Sending GET request to URL : " + apiUrl);
                    HttpURLConnection con = (HttpURLConnection) new URL(apiUrl).openConnection();
                    con.setRequestMethod("GET");
                    Log.d(TAG, "Response: " + con.getResponseCode() + ", " + con.getResponseMessage());
                    // Parse JSON
                    changes.addAll(new ChangelogParser().parseJSON(con.getInputStream()));
                } catch (IOException e) {
                    Log.e(TAG, "Parse error!", e);
                    return null;
                }
                Log.v(TAG, "Successfully parsed REST API in " +
                        (System.currentTimeMillis() - time) + "ms");
                start += n; // skip n changes in next iteration
            }
            return changes;
        }

        // Runs on the UI thread
        @Override
        protected void onPostExecute(List<Change> fetchedChanges) {
            if (fetchedChanges != null) {
                List oldChanges = mAdapter.getDataset();
                if (oldChanges.isEmpty() || !fetchedChanges.get(0).equals(oldChanges.get(0))) {
                    // Update the list
                    mAdapter.clear();
                    mAdapter.addAll(fetchedChanges);
                    // Update cache
                    new CacheTask().execute(fetchedChanges);
                } else {
                    Log.d(TAG, "Nothing changed");
                }
            }

            // Delay refreshing animation just for the show
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    mSwipeRefreshLayout.setRefreshing(false);
                }
            }, 400);
        }
    }

    private class CacheTask extends AsyncTask<List, Void, Void> {
        @Override
        protected Void doInBackground(List... list) {
            try {
                FileOutputStream fileOutputStream = new FileOutputStream(new File(getCacheDir(), "cache"));
                ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream);
                for (Object obj : list[0]) {
                    objectOutputStream.writeObject(obj);
                }
                objectOutputStream.writeObject(null);
                objectOutputStream.close();
                Log.d(TAG, "Successfully cached data");
            } catch (IOException e) {
                Log.e(TAG, "Error while writing cache");
            }
            return null;
        }
    }

}
