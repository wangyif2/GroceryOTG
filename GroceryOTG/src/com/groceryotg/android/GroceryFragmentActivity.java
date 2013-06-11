package com.groceryotg.android;

import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Toast;

import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.actionbarsherlock.widget.SearchView;
import com.groceryotg.android.database.CategoryTable;
import com.groceryotg.android.database.StoreParentTable;
import com.groceryotg.android.database.contentprovider.GroceryotgProvider;
import com.groceryotg.android.fragment.CategoryGridFragment;
import com.groceryotg.android.fragment.GroceryListFragment;
import com.groceryotg.android.services.NetworkHandler;
import com.groceryotg.android.utils.GroceryOTGUtils;
import com.groceryotg.android.utils.RefreshAnimation;
import com.slidingmenu.lib.SlidingMenu;

import java.util.HashMap;
import java.util.Map;

public class GroceryFragmentActivity extends SherlockFragmentActivity {
	public static String EXTRA_LAUNCH_PAGE = "extra_launch_page";
	
    static HashMap<Integer, String> categories;

    public static Context mContext;
    public static ViewPager mPager;
    GroceryAdapter mAdapter;
    SlidingMenu mSlidingMenu;
    RefreshStatusReceiver mRefreshStatusReceiver;
    MenuItem refreshItem;
    static Menu menu;

    public static String myQuery;

    private final int OFFPAGE_LIMIT = 0;

    public static Map<Integer, String> storeNames;
    public static Double mPriceRangeMin;
    public static Double mPriceRangeMax;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.grocery_pager);

        categories = getCategoryInfo();
        mContext = this;
        setMyQuery("");

        setStoreInformation();

        configActionBar();

        configViewPager();

        mSlidingMenu = GroceryOTGUtils.createSlidingMenu(this);
        GroceryOTGUtils.registerSlidingMenu(mSlidingMenu, this);
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
    	Log.i("GroceryOTG", "Handing intent");
    	clearSearch();
    	
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            // Gets the search query from the voice recognizer intent
            String query = intent.getStringExtra(SearchManager.QUERY);

            if (mPager.getCurrentItem() > 0) {
                // Set the search box text to the received query and submit the search
                // from within the fragment if not on the category overview page:
                mAdapter.getFragment(mPager.getCurrentItem()).handleVoiceSearch(query);
            } else {
                // If on the home page and doing a global search, send the intent
                // to the GlobalSearchActivity
                Intent globalSearchIntent = new Intent(this, GlobalSearchFragmentActivity.class);
                GroceryOTGUtils.copyIntentData(intent, globalSearchIntent);
                globalSearchIntent.putExtra(GlobalSearchFragmentActivity.GLOBAL_SEARCH, true);
                startActivity(globalSearchIntent);
            }
        }
        
        Bundle extras = intent.getExtras();
        if (extras != null) {
        	Log.i("GroceryOTG", Integer.toString(extras.getInt(GroceryFragmentActivity.EXTRA_LAUNCH_PAGE)));
        	mPager.setCurrentItem(extras.getInt(GroceryFragmentActivity.EXTRA_LAUNCH_PAGE));
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
        inflater.inflate(R.menu.grocery_pager_menu, menu);
        GroceryFragmentActivity.menu = menu;

        // Get the SearchView and set the searchable configuration
        SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        SearchView searchView = (SearchView) menu.findItem(R.id.search).getActionView();
        searchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
        searchView.setIconifiedByDefault(true);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.refresh:
                refreshCurrentPager();
                return true;
            case R.id.map:
                GroceryOTGUtils.launchMapActivity(this);
                return true;
            case R.id.shop_cart:
            	GroceryOTGUtils.launchShopCartActivity(this);
                return true;
            case android.R.id.home:
            	// When home is pressed
            	if (mPager.getCurrentItem() == 0) {
            		if (mSlidingMenu.isMenuShowing())
            			mSlidingMenu.showContent();
            		else
            			mSlidingMenu.showMenu();
            	} else {
            		mPager.setCurrentItem(0);
            	}
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private HashMap<Integer, String> getCategoryInfo() {
        HashMap<Integer, String> categories = new HashMap<Integer, String>();
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
        storeNames = new HashMap<Integer, String>(); // {storeParentId, storeParentName}

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

    public static void setMyQuery(String mQuery) {
        GroceryFragmentActivity.myQuery = mQuery;
    }

    private void configActionBar() {
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    private void configViewPager() {
        mPager = (ViewPager) findViewById(R.id.pager);

        mAdapter = new GroceryAdapter(getSupportFragmentManager());
        mPager.setAdapter(mAdapter);
        mPager.setOffscreenPageLimit(OFFPAGE_LIMIT);
    }
    
    private static void clearSearch() {
    	// Clear any open searches
    	MenuItem searchItem = menu.findItem(R.id.search);
    	if (searchItem != null) {
    		searchItem.collapseActionView();
    	}
    }

    private void refreshCurrentPager() {
        Toast t = Toast.makeText(this, "Starting fetching new items...", Toast.LENGTH_LONG);
        t.show();

        Intent intent = new Intent(mContext, NetworkHandler.class);
        if (mPager.getCurrentItem() == 0) {
            refreshItem = menu.findItem(R.id.refresh);
            RefreshAnimation.refreshIcon(mContext, true, refreshItem);
            intent.putExtra(NetworkHandler.REFRESH_CONTENT, NetworkHandler.CAT);
        } else
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

        private static int currentPage;

        private static final int PAGE_SELECTED = 0;

        private static final int POSITION_CATEGORY = 0;
        private static final String TITLE_PAGER_CATEGORY = "categories overview";

        private HashMap<Integer, GroceryListFragment> mPageReferenceMap;

        public GroceryAdapter(FragmentManager fm) {
            super(fm);
            mPageReferenceMap = new HashMap<Integer, GroceryListFragment>();
            mPager.setOnPageChangeListener(this);
        }

        @Override
        public CharSequence getPageTitle(int position) {
            if (position == POSITION_CATEGORY) {
                return TITLE_PAGER_CATEGORY;
            }
            else
                // The hashmap is offset by the position of myflyer pager
                return categories.get(position);
        }

        @Override
        public Fragment getItem(int i) {
            if (i == POSITION_CATEGORY) {
                return new CategoryGridFragment();
            } else {
                GroceryListFragment myFragment;
                if (mPageReferenceMap.get(i) == null) {
                    myFragment = GroceryListFragment.newInstance(i);
                    mPageReferenceMap.put(i, myFragment);
                    return myFragment;
                } else
                    return mPageReferenceMap.get(i);
            }
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            super.destroyItem(container, position, object);
            mPageReferenceMap.remove(position);
        }

        @Override
        public int getCount() {
            //the plus 1 here is for the overview front page
            return categories.size() + 1;
        }

        public GroceryListFragment getFragment(int key) {
            return mPageReferenceMap.get(key);
        }

        @Override
        public void onPageScrolled(int i, float v, int i2) {
        }

        //TODO: refactor hack to improve scroll perf
        @Override
        public void onPageSelected(int i) {
            currentPage = i;
        }

        @Override
        public void onPageScrollStateChanged(int i) {
            // if Page Scroll state is *SELECTED*, we can start loading
            if (i == PAGE_SELECTED) {
                if (currentPage == GroceryAdapter.POSITION_CATEGORY) {
                } else {
                	clearSearch();
                    getFragment(currentPage).loadDataWithQuery(false, "");
                }
            }
        }
    }
}
