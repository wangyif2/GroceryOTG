package com.groceryotg.android;

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
import android.util.SparseIntArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.groceryotg.android.database.CategoryTable;
import com.groceryotg.android.database.StoreParentTable;
import com.groceryotg.android.database.contentprovider.GroceryotgProvider;
import com.groceryotg.android.fragment.CategoryGridFragment;
import com.groceryotg.android.fragment.GroceryListFragment;
import com.groceryotg.android.utils.GroceryOTGUtils;
import com.groceryotg.android.services.NetworkHandler;
import com.groceryotg.android.utils.RefreshAnimation;
import com.slidingmenu.lib.SlidingMenu;

import java.util.HashMap;
import java.util.Map;

/**
 * User: robert
 * Date: 16/03/13
 */
public class GroceryFragmentActivity extends SherlockFragmentActivity {
    static HashMap<Integer, String> categories;

    public static Context mContext;
    public static ViewPager mPager;
    GroceryAdapter mAdapter;
    SlidingMenu mSlidingMenu;
    RefreshStatusReceiver mRefreshStatusReceiver;
    MenuItem refreshItem;
    Menu menu;

    public static String myQuery;

    public static Map<Integer, String> storeNames;
    public static SparseIntArray storeSelected;

    public static Double mPriceRangeMin;
    public static Double mPriceRangeMax;

    public static final Integer SELECTED = 1;
    public static final Integer NOT_SELECTED = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.grocery_pager);

        Bundle extras = getIntent().getExtras();
        categories = getCategoryInfo();
        mContext = this;
        setMyQuery("");

        setStoreInformation();

        configActionBar();

        configViewPager();

        configSlidingMenu();
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
        this.menu = menu;
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.refresh:
                refreshCurrentPager();
                return true;
            case R.id.map:
                launchMapActivity();
                return true;
            case R.id.shop_cart:
                launchShopCartActivity();
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
        storeSelected = new SparseIntArray();
        storeNames = new HashMap<Integer, String>();

        Cursor storeCursor = GroceryOTGUtils.getStoreParentNames(this);
        if (storeCursor != null) {
            storeCursor.moveToFirst();
            while (!storeCursor.isAfterLast()) {
                storeSelected.put(storeCursor.getInt(storeCursor.getColumnIndex(StoreParentTable.COLUMN_STORE_PARENT_ID)), SELECTED);
                storeNames.put(storeCursor.getInt(storeCursor.getColumnIndex(StoreParentTable.COLUMN_STORE_PARENT_ID)),
                                storeCursor.getString(storeCursor.getColumnIndex(StoreParentTable.COLUMN_STORE_PARENT_NAME)));
                storeCursor.moveToNext();
            }
        }
    }

    public void setMyQuery(String mQuery) {
        this.myQuery = mQuery;
    }

    private void configActionBar() {
        getSupportActionBar().setDisplayHomeAsUpEnabled(false);
    }

    private void configViewPager() {
        mAdapter = new GroceryAdapter(getSupportFragmentManager());

        mPager = (ViewPager) findViewById(R.id.pager);
        mPager.setAdapter(mAdapter);
    }

    private void configSlidingMenu() {
        mSlidingMenu = new SlidingMenu(this);
        mSlidingMenu.setMode(SlidingMenu.LEFT);
        mSlidingMenu.setShadowWidthRes(R.dimen.shadow_width);
        mSlidingMenu.setShadowDrawable(R.drawable.shadow);
        mSlidingMenu.setBehindOffsetRes(R.dimen.slidingmenu_offset);
        mSlidingMenu.setFadeDegree(0.35f);
        mSlidingMenu.setTouchModeAbove(SlidingMenu.TOUCHMODE_MARGIN);
        mSlidingMenu.attachToActivity(this, SlidingMenu.SLIDING_CONTENT);
        mSlidingMenu.setMenu(R.layout.menu_frame);

        // Populate the SlidingMenu
        String[] slidingMenuItems = new String[]{getString(R.string.slidingmenu_item_cat),
                getString(R.string.slidingmenu_item_cart),
                getString(R.string.slidingmenu_item_map),
                getString(R.string.slidingmenu_item_sync),
                getString(R.string.slidingmenu_item_settings),
                getString(R.string.slidingmenu_item_about)};

        ListView menuView = (ListView) findViewById(R.id.menu_items);
        ArrayAdapter<String> menuAdapter = new ArrayAdapter<String>(this,
                R.layout.menu_item, android.R.id.text1, slidingMenuItems);
        menuView.setAdapter(menuAdapter);

        menuView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view,
                                    int position, long id) {
                // Switch activity based on what mSlidingMenu item the user selected
                TextView textView = (TextView) view;
                String selectedItem = textView.getText().toString();

                if (selectedItem.equalsIgnoreCase(getString(R.string.slidingmenu_item_cat))) {
                    // Selected Categories
                    if (mSlidingMenu.isMenuShowing())
                        mSlidingMenu.showContent();
                    mPager.setCurrentItem(0);
                } else if (selectedItem.equalsIgnoreCase(getString(R.string.slidingmenu_item_cart))) {
                    // Selected Shopping Cart
                    launchShopCartActivity();
                } else if (selectedItem.equalsIgnoreCase(getString(R.string.slidingmenu_item_map))) {
                    // Selected Map
                    launchMapActivity();
                } else if (selectedItem.equalsIgnoreCase(getString(R.string.slidingmenu_item_sync))) {
                    // Selected Sync
                    refreshCurrentPager();
                } else if (selectedItem.equalsIgnoreCase(getString(R.string.slidingmenu_item_settings))) {
                    // Selected Settings
                    if (mSlidingMenu.isMenuShowing())
                        mSlidingMenu.showContent();
                } else if (selectedItem.equalsIgnoreCase(getString(R.string.slidingmenu_item_about))) {
                    // Selected About
                    //startActivity(new Intent(CategoryOverView.this, About.class));
                    if (mSlidingMenu.isMenuShowing())
                        mSlidingMenu.showContent();
                }
            }
        });
    }

    private void launchShopCartActivity() {
        Intent intent = new Intent(this, ShopCartOverView.class);
        startActivity(intent);
    }

    private void launchMapActivity() {
        Intent intent = new Intent(this, GroceryMapView.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private void refreshCurrentPager() {
        if (mSlidingMenu.isMenuShowing())
            mSlidingMenu.showContent();

        refreshItem = menu.findItem(R.id.refresh);
        RefreshAnimation.refreshIcon(mContext, true, refreshItem);

        Intent intent = new Intent(mContext, NetworkHandler.class);
        if (mPager.getCurrentItem() == 0)
            intent.putExtra(NetworkHandler.REFRESH_CONTENT, NetworkHandler.CAT);
        else
            intent.putExtra(NetworkHandler.REFRESH_CONTENT, NetworkHandler.GRO);
        startService(intent);
    }

    private class RefreshStatusReceiver extends BroadcastReceiver {
        private RefreshStatusReceiver() {

        }

        @Override
        public void onReceive(Context context, Intent intent) {
            int resultCode = intent.getBundleExtra("bundle").getInt(NetworkHandler.CONNECTION_STATE);

            Toast toast = null;
            RefreshAnimation.refreshIcon(context, false, refreshItem);
            if (resultCode == NetworkHandler.CONNECTION) {
                toast = Toast.makeText(mContext, "Groceries Updated", Toast.LENGTH_LONG);
            } else if (resultCode == NetworkHandler.NO_CONNECTION) {
                toast = Toast.makeText(mContext, "No Internet Connection", Toast.LENGTH_LONG);
            }
            assert toast != null;
            toast.show();
        }
    }

    public static class GroceryAdapter extends FragmentStatePagerAdapter {

        private HashMap<Integer, GroceryListFragment> mPageReferenceMap;

        public GroceryAdapter(FragmentManager fm) {
            super(fm);
            mPageReferenceMap = new HashMap<Integer, GroceryListFragment>();
        }

        @Override
        public CharSequence getPageTitle(int position) {
            if (position == 0) {
                return "categories overview";
            } else
                return categories.get(position);
        }

        @Override
        public Fragment getItem(int i) {
            if (i == 0) {
                return new CategoryGridFragment();
            } else {
                GroceryListFragment myFragment = GroceryListFragment.newInstance(i);
                mPageReferenceMap.put(i, myFragment);
                return myFragment;
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
    }
}