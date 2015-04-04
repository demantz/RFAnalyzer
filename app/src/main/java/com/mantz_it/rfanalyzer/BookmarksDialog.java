package com.mantz_it.rfanalyzer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.database.Cursor;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;

import com.mantz_it.rfanalyzer.BookmarksContract.Bookmarks;
import com.mantz_it.rfanalyzer.BookmarksContract.BookmarkCategories;

/**
 * Created by dennis on 04/04/15.
 */
public class BookmarksDialog implements View.OnClickListener, AdapterView.OnItemClickListener {
	private static final String LOGTAG = "BookmarksDialog";
	private Activity activity;
	private AlertDialog dialog;

	private LinearLayout ll_root;
	private FrameLayout fl_list;
	private LinearLayout ll_bookmarks_list;
	private Button bt_addBookmark;
	private Button bt_addCategory;
	private ListView lv_categories;
	private ListView lv_bookmarks;

	private SimpleCursorAdapter categoriesAdapter;
	private SimpleCursorAdapter bookmarksAdapter;
	private Cursor categoriesCursor;
	private Cursor bookmarksCursor;

	public BookmarksDialog(Activity activity) {
		this.activity = activity;

		// Get references to the GUI components:
		ll_root = (LinearLayout) activity.getLayoutInflater().inflate(R.layout.bookmarks, null);
		bt_addBookmark = (Button) ll_root.findViewById(R.id.bt_bookmarks_addBookmark);
		bt_addCategory = (Button) ll_root.findViewById(R.id.bt_bookmarks_addCategory);
		fl_list = (FrameLayout) ll_root.findViewById(R.id.fl_bookmarks_list);
		lv_categories = (ListView) ll_root.findViewById(R.id.lv_bookmarks_categories);
		ll_bookmarks_list = (LinearLayout) ll_root.findViewById(R.id.ll_bookmarks_list);
		lv_bookmarks = (ListView) ll_root.findViewById(R.id.lv_bookmarks);

		// Set up all GUI components
		bt_addBookmark.setOnClickListener(this);
		bt_addCategory.setOnClickListener(this);
		lv_categories.setOnItemClickListener(this);
		fl_list.bringChildToFront(lv_categories);
		ll_bookmarks_list.setVisibility(View.GONE);

		// DEBUG: query the db:
		categoriesCursor = activity.getContentResolver().query(BookmarksContract.BookmarkCategories.CONTENT_URI, null, null, new String[] {}, null);

		// Set up the cursor adapter for the categories
		categoriesAdapter = new SimpleCursorAdapter(activity, android.R.layout.simple_list_item_1,
				categoriesCursor,
				new String[]{BookmarkCategories.COLUMN_NAME_CATEGORY_NAME},
				new int[]{android.R.id.text1},
				0);
		lv_categories.setAdapter(categoriesAdapter);

		// Set up the cursor adapter for the bookmarks
		bookmarksAdapter = new SimpleCursorAdapter(activity, android.R.layout.simple_list_item_1,
				bookmarksCursor,
				new String[]{BookmarksContract.Bookmarks.COLUMN_NAME_NAME},
				new int[]{android.R.id.text1},
				0);
		lv_bookmarks.setAdapter(bookmarksAdapter);

		// create and show dialog:
		dialog = new AlertDialog.Builder(activity)
				.setTitle("Bookmarks")
				.setView(ll_root)
				.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						// do nothing
					}
				})
				.create();
		dialog.show();
		dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
	}

	@Override
	public void onClick(View v) {

	}

	@Override
	public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
		if(parent == lv_categories) {
			// DEBUG
			String selection = Bookmarks.COLUMN_NAME_CATEGORY_ID + " == ?";
			String[] selectionArgs = {"" + id};
			bookmarksCursor = activity.getContentResolver().query(Bookmarks.CONTENT_URI, null, selection, selectionArgs, null);
			bookmarksAdapter.changeCursor(bookmarksCursor);

			fl_list.bringChildToFront(ll_bookmarks_list);
			lv_categories.setVisibility(View.GONE);
			ll_bookmarks_list.setVisibility(View.VISIBLE);
		} else if(parent == lv_bookmarks) {

		} else {
			Log.e(LOGTAG, "onItemClick: Unknown parent: " + parent);
		}
	}
}
