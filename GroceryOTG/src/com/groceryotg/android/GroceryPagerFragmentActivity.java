package com.groceryotg.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Bundle;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.DrawerLayout;
import android.util.SparseArray;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.Toast;

import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.groceryotg.android.database.CategoryTable;
import com.groceryotg.android.database.StoreParentTable;
import com.groceryotg.android.database.contentprovider.GroceryotgProvider;
import com.groceryotg.android.fragment.GroceryListFragment;
import com.groceryotg.android.services.NetworkHandler;
import com.groceryotg.android.utils.GroceryOTGUtils;
import com.groceryotg.android.utils.RefreshAnimation;

public class GroceryPagerFragmentActivity extends SherlockFragmentActivity {
	public static String EXTRA_LAUNCH_PAGE = "extra_launch_page";
	
    public static SparseArray<String> categories;
    public static SparseArray<String> storeNames;

    public static Context mContext;
    public static ViewPager mPager;
    
    private DrawerLayout mDrawerLayout;
    private ListView mDrawerList;
    
    GroceryAdapter mAdapter;
    RefreshStatusReceiver mRefreshStatusReceiver;
    MenuItem refreshItem;

    private final int OFFPAGE_LIMIT = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.grocery_pager_activity);

        categories = getCategoryInfo();
        mContext = this;

        setStoreInformation();

        GroceryOTGUtils.NavigationDrawerBundle drawerBundle = GroceryOTGUtils.configNavigationDrawer(this, false, R.string.title_grocery_pager);
        this.mDrawerLayout = drawerBundle.getDrawerLayout();
        this.mDrawerList = drawerBundle.getDrawerList();

        configViewPager();
        
        handleIntent(getIntent());
    }
    
    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras != null) {
        	int position = extras.getInt(GroceryPagerFragmentActivity.EXTRA_LAUNCH_PAGE);
        	mPager.setCurrentItem(position);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mRefreshStatusReceiver = new RefreshStatusReceiver();
        IntentFilter mStatusIntentFilter = new IntentFilter(NetworkHandler.REFRESH_COMPLETED_ACTION);
        mStatusIntentFilter.addCategory(Intent.CATEGORY_DEFAULT);
        LocalBroadcastManager.getInstance(this).registerReceiver(mRefreshStatusReceiver, mStatusIntentFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mRefreshStatusReceiver);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getSupportMenuInflater();
        inflater.inflate(R.menu.grocery_pager_activity_menu, menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.refresh:
                refreshCurrentPager();
                return true;
            case android.R.id.home:
            	if (mDrawerLayout.isDrawerOpen(mDrawerList))
            		mDrawerLayout.closeDrawer(mDrawerList);
            	else {
            		// Specify the parent activity
                	Intent parentActivityIntent = new Intent(this, CategoryTopFragmentActivity.class);
                	parentActivityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | 
                								Intent.FLAG_ACTIVITY_NEW_TASK);
                	startActivity(parentActivityIntent);
                	this.finish();
            	}
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private SparseArray<String> getCategoryInfo() {
        SparseArray<String> categories = new SparseArray<String>();
        Cursor c = getContentResolver().query(GroceryotgProvider.CONTENT_URI_CAT, null, null, null, null);

        c.moveToFirst();
        while (!c.isAfterLast()) {
            categories.put(
                    c.getInt(c.getColumnIndexOrThrow(CategoryTable.COLUMN_CATEGORY_ID)),
                    c.getString(c.getColumnIndexOrThrow(CategoryTable.COLUMN_CATEGORY_NAME)));
            c.moveToNext();
        }
        return categories;
    }

    private void setStoreInformation() {
        // Initialize the list of stores from database
        storeNames = new SparseArray<String>(); // {storeParentId, storeParentName}

        Cursor storeCursor = GroceryOTGUtils.getStoreParentNamesCursor(this);
        if (storeCursor != null) {
            storeCursor.moveToFirst();
            while (!storeCursor.isAfterLast()) {
                storeNames.put(storeCursor.getInt(storeCursor.getColumnIndex(StoreParentTable.COLUMN_STORE_PARENT_ID)),
                        storeCursor.getString(storeCursor.getColumnIndex(StoreParentTable.COLUMN_STORE_PARENT_NAME)));
                storeCursor.moveToNext();
            }
        }
    }

    private void configViewPager() {
        mPager = (ViewPager) findViewById(R.id.pager);

        mAdapter = new GroceryAdapter(getSupportFragmentManager());
        mPager.setAdapter(mAdapter);
        mPager.setOffscreenPageLimit(OFFPAGE_LIMIT);
    }
    
    private void refreshCurrentPager() {
        Toast t = Toast.makeText(this, "Fetching new items...", Toast.LENGTH_LONG);
        t.show();

        Intent intent = new Intent(mContext, NetworkHandler.class);
        intent.putExtra(NetworkHandler.REFRESH_CONTENT, NetworkHandler.GRO);
        startService(intent);
    }

    private class RefreshStatusReceiver extends BroadcastReceiver {
        private RefreshStatusReceiver() {

        }

        @Override
        public void onReceive(Context context, Intent intent) {
            int resultCode = intent.getBundleExtra("bundle").getInt(NetworkHandler.CONNECTION_STATE);
            int requestType = intent.getBundleExtra("bundle").getInt(NetworkHandler.REQUEST_TYPE);

            Toast toast = null;
            if (requestType == NetworkHandler.CAT) {
                RefreshAnimation.refreshIcon(context, false, refreshItem);
            }
            if (resultCode == NetworkHandler.CONNECTION) {
                toast = Toast.makeText(mContext, "Groceries Updated", Toast.LENGTH_LONG);
            } else if (resultCode == NetworkHandler.NO_CONNECTION) {
                RefreshAnimation.refreshIcon(context, false, refreshItem);
                toast = Toast.makeText(mContext, "No Internet Connection", Toast.LENGTH_LONG);
            }
            assert toast != null;
            toast.show();
        }
    }

    public static class GroceryAdapter extends FragmentStatePagerAdapter implements ViewPager.OnPageChangeListener {
        private SparseArray<GroceryListFragment> mPageReferenceMap;
        
        int mCurrentPage = 0;

        public GroceryAdapter(FragmentManager fm) {
            super(fm);
            mPageReferenceMap = new SparseArray<GroceryListFragment>();
            mPager.setOnPageChangeListener(this);
        }
        
        @Override
        public CharSequence getPageTitle(int position) {
            // The hashmap is offset by one
            return categories.valueAt(position);
        }

        @Override
        public GroceryListFragment getItem(int position) {
            GroceryListFragment myFragment;
            if (mPageReferenceMap.get(position) == null) {
                myFragment = GroceryListFragment.newInstance(categories.keyAt(position));
                mPageReferenceMap.put(position, myFragment);
                return myFragment;
            } else
                return mPageReferenceMap.get(position);
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            super.destroyItem(container, position, object);
            mPageReferenceMap.remove(position);
        }

        @Override
        public int getCount() {
            return categories.size();
        }

        @Override
		public void onPageScrollStateChanged(int state) {
		}
        @Override
        public void onPageScrolled(int i, float v, int i2) {
        }

        @Override
        public void onPageSelected(int position) {
        	mCurrentPage = position;
        }
    }
}