package com.groceryotg.android.fragment;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.support.v4.app.ActionBarDrawerToggle;
import android.support.v4.content.CursorLoader;
import android.support.v4.widget.DrawerLayout;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.ListView;

import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.MenuItem;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMap.OnCameraChangeListener;
import com.google.android.gms.maps.GoogleMapOptions;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.*;
import com.groceryotg.android.MapFragmentActivity;
import com.groceryotg.android.R;
import com.groceryotg.android.database.StoreParentTable;
import com.groceryotg.android.database.StoreTable;
import com.groceryotg.android.database.contentprovider.GroceryotgProvider;
import com.groceryotg.android.settings.SettingsManager;
import com.groceryotg.android.utils.GroceryOTGUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MapFragment extends SupportMapFragment {
    Activity mActivity;
    
    private GoogleMap mMap = null;
    private Map<String, Integer> mIconMap = new HashMap<String, Integer>();
    private ArrayList<Marker> mMapMarkers = new ArrayList<Marker>();
    
    private ArrayList<Integer> filterStoreParents = null;
    private ArrayList<Integer> filterStores = null;

    @Override
    public void onAttach(Activity activity) {
    	super.onAttach(activity);
    	this.mActivity = activity;
    }
    
    @Override
	public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Log.i("GroceryOTG", "Creating map frag");
        
        Bundle args = getArguments();
        if (args != null) {
	        this.filterStoreParents = args.getIntegerArrayList(MapFragmentActivity.EXTRA_FILTER_STORE_PARENT);
	        this.filterStores = args.getIntegerArrayList(MapFragmentActivity.EXTRA_FILTER_STORE);
        }
    }
    
    @Override
    public void onActivityCreated (Bundle savedInstanceState) {
    	super.onActivityCreated(savedInstanceState);
    	
	    buildIconMap(mActivity);
	    Location lastKnownLocation = getLastKnownLocation();
	    Cursor storeLocations = getFilteredStores(mActivity).loadInBackground();
	    
	    mMap = this.getMap();
	    if (mMap != null) {
	    	Log.i("GroceryOTG", "Map is not null");
	        mMap.setOnCameraChangeListener(getCameraChangeListener());
	
	        if (lastKnownLocation != null) {
	            // add a marker at the current location
	            buildUserMarker(mActivity, mMap, getString(R.string.map_usermarker), lastKnownLocation);
	            // move the camera to the current location
	            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude()), MapFragmentActivity.CAM_ZOOM));
	        }
	
	        buildStoreMarkers(mActivity, storeLocations, mMap);
	    }
    }
    
    private void buildIconMap(Context context) {
    	Cursor parents = GroceryOTGUtils.getStoreParentNamesCursor(context);
    	parents.moveToFirst();
    	while (!parents.isAfterLast()) {
    		String name = parents.getString(parents.getColumnIndex(StoreParentTable.COLUMN_STORE_PARENT_NAME));
    		int markerImageID = context.getResources().getIdentifier("ic_mapmarker_" + name, "drawable", mActivity.getPackageName());
    		if (markerImageID != 0) {
    			mIconMap.put(name, markerImageID);
    		}
    		parents.moveToNext();
    	}
    }

    private void buildUserMarker(Context context, GoogleMap map, String str, Location loc) {
    	LatLng ll = new LatLng(loc.getLatitude(), loc.getLongitude());
        map.addMarker(new MarkerOptions()
                .position(ll)
                .title(str)
                .draggable(false)
                .visible(true));
        
		CircleOptions circleOptions = new CircleOptions()
			.center(ll)
			.radius(loc.getAccuracy())
			.fillColor(0x100000FF)
			.strokeColor(0xFF0000FF)
			.strokeWidth(2);
		
		mMap.addCircle(circleOptions);
    }

    private void buildStoreMarkers(Context context, Cursor storeLocations, GoogleMap map) {
        storeLocations.moveToFirst();
        int storeNum = storeLocations.getColumnCount();
        while (!storeLocations.isAfterLast()) {
            for (int i = 0; i < storeNum; i++) {
            	int storeID = storeLocations.getInt(storeLocations.getColumnIndex(StoreTable.COLUMN_STORE_ID));
            	int storeParentID = storeLocations.getInt(storeLocations.getColumnIndex(StoreParentTable.COLUMN_STORE_PARENT_ID));
                String storeName = storeLocations.getString(storeLocations.getColumnIndex(StoreParentTable.COLUMN_STORE_PARENT_NAME));
                String storeAdr = storeLocations.getString(storeLocations.getColumnIndex(StoreTable.COLUMN_STORE_ADDR));
                double storeLat = storeLocations.getDouble(storeLocations.getColumnIndex(StoreTable.COLUMN_STORE_LATITUDE));
                double storeLng = storeLocations.getDouble(storeLocations.getColumnIndex(StoreTable.COLUMN_STORE_LONGITUDE));
                
                LatLng storeLatLng = new LatLng(storeLat, storeLng);
                
                // Now do filtering
                boolean isIncluded = true;
                if (this.filterStoreParents != null) {
                	isIncluded = false;
                	if (this.filterStoreParents.contains(storeParentID))
                		isIncluded = true;
                }
                if (this.filterStores != null) {
                	isIncluded = false;
                	if (this.filterStores.contains(storeID))
                		isIncluded = true;
                }
                
                if (isIncluded) {
                	buildStoreMarker(context, map, storeName, storeAdr, storeLatLng);
                }
            }
            storeLocations.moveToNext();
        }
    }

    private void buildStoreMarker(Context context, GoogleMap map, String storeName, String storeAdr, LatLng storeLatLng) {
    	MarkerOptions markerOptions = new MarkerOptions()
        	.position(storeLatLng)
        	.title(storeName)
        	.snippet(storeAdr)
        	.draggable(false)
        	.visible(false);
        
        if (mIconMap.containsKey(storeName)) {
            markerOptions = markerOptions.icon(BitmapDescriptorFactory.fromResource(mIconMap.get(storeName)));
        }
        Marker marker = map.addMarker(markerOptions);
        mMapMarkers.add(marker);
    }

    private Location getLastKnownLocation() {
        LocationManager locationManager = (LocationManager) mActivity.getSystemService(Context.LOCATION_SERVICE);
        Location loc = null;
        if (locationManager != null) {
        	loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        }
        return loc;
    }

    private OnCameraChangeListener getCameraChangeListener() {
    	return new OnCameraChangeListener() {
    		@Override
    		public void onCameraChange(CameraPosition position) {
    			showItemsOnMap();
    		}
    	};
    }

    private void showItemsOnMap() {
    	if(this.mMap != null) {
    		LatLngBounds bounds = this.mMap.getProjection().getVisibleRegion().latLngBounds;
    		
    		for(Marker marker : this.mMapMarkers) {
    			LatLng pos = marker.getPosition();
    			if(bounds.contains(new LatLng(pos.latitude, pos.longitude))) {
    				if (!marker.isVisible()) {
    					marker.setVisible(true);
    				}
    			} else {
    				if (marker.isVisible()) {
    					marker.setVisible(false);
    				}
    			}
    		}
    	}
    }

    private CursorLoader getFilteredStores(Context context) {
    	List<String> selectionArgs = new ArrayList<String>();
        String[] projection = {StoreTable.TABLE_STORE+"."+StoreTable.COLUMN_STORE_ID,
        		StoreParentTable.TABLE_STORE_PARENT+"."+StoreParentTable.COLUMN_STORE_PARENT_ID,
        		StoreParentTable.TABLE_STORE_PARENT+"."+StoreParentTable.COLUMN_STORE_PARENT_NAME,
        		StoreTable.TABLE_STORE+"."+StoreTable.COLUMN_STORE_ADDR,
        		StoreTable.TABLE_STORE+"."+StoreTable.COLUMN_STORE_LATITUDE,
        		StoreTable.TABLE_STORE+"."+StoreTable.COLUMN_STORE_LONGITUDE};
        String selection = "";
        
	    SparseBooleanArray selectedStores = SettingsManager.getStoreFilter(context);
	    if (selectedStores != null && selectedStores.size() > 0) {
	        // Go through selected stores and add them to query
	        String storeSelection = "";
	        for (int storeNum = 0; storeNum < selectedStores.size(); storeNum++) {
	            if (selectedStores.valueAt(storeNum) == true) {
	                if (storeSelection.isEmpty()) {
	                    storeSelection += StoreParentTable.TABLE_STORE_PARENT + "." + StoreParentTable.COLUMN_STORE_PARENT_ID + " = ?";
	                } else {
	                    storeSelection += " OR " + StoreParentTable.TABLE_STORE_PARENT + "." + StoreParentTable.COLUMN_STORE_PARENT_ID + " = ?";
	                }
	                selectionArgs.add(((Integer) selectedStores.keyAt(storeNum)).toString());
	            }
	        }
	        if (!storeSelection.isEmpty()) {
	            selection += storeSelection;
	        }
	    }
	
	    final String[] selectionArgsArr = new String[selectionArgs.size()];
	    selectionArgs.toArray(selectionArgsArr);
	
	    return new CursorLoader(context, GroceryotgProvider.CONTENT_URI_STO_JOIN_STOREPARENT, projection, selection, selectionArgsArr, null);
	}
    
}
