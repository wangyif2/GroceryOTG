package com.groceryotg.android.fragment;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import com.actionbarsherlock.app.SherlockFragment;
import com.groceryotg.android.R;
import com.groceryotg.android.database.CartTable;
import com.groceryotg.android.database.contentprovider.GroceryotgProvider;

public class ShopCartAddTabVoiceFragment extends SherlockFragment {
	private Context mContext;
	
	private TextView mText;

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		this.mContext = activity;
	}
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
	}
	
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.shopcart_add_tab_voice, container, false);
		
		mText = (TextView) v.findViewById(R.id.voice_text);

		Button confirmButton = (Button) v.findViewById(R.id.positive_button);
		confirmButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				addItem();
			}
		});
		Button clearButton = (Button) v.findViewById(R.id.negative_button);
		clearButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				clearFocus();
			}
		});
		
		return v;
	}

	private void makeToast(String text) {
		Toast.makeText(mContext, text, Toast.LENGTH_LONG).show();
	}
	
	private void clearFocus() {
		// clear the next in the edit box
		mText.setText("");
	}
	
	private void addItem() {
		String name = mText.getText().toString();
		
		clearFocus();
		
		if (TextUtils.isEmpty(name)) {
			makeToast("Please enter a name");
			return;
		}
		
		makeToast(getString(R.string.cart_shoplist_added));

		ContentValues values = new ContentValues();
		values.put(CartTable.COLUMN_CART_GROCERY_NAME, name);
		values.put(CartTable.COLUMN_CART_FLAG_SHOPLIST, CartTable.FLAG_TRUE);
		values.put(CartTable.COLUMN_CART_FLAG_WATCHLIST, CartTable.FLAG_FALSE);

		mContext.getContentResolver().insert(GroceryotgProvider.CONTENT_URI_CART_ITEM, values);
	}
}
