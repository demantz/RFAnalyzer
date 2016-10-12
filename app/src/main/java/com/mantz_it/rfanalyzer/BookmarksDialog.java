package com.mantz_it.rfanalyzer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CursorAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.SimpleCursorAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.mantz_it.rfanalyzer.BookmarksContract.BookmarkCategories;
import com.mantz_it.rfanalyzer.BookmarksContract.Bookmarks;

/**
 * Created by dennis on 04/04/15.
 */
public class BookmarksDialog implements View.OnClickListener, AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {
	private static final String LOGTAG = "BookmarksDialog";
	private Activity activity;
	private RFControlInterface rfControlInterface;
	private AlertDialog dialog;

	private LinearLayout ll_root;
	private FrameLayout fl_list;
	private LinearLayout ll_bookmarks_list;
	private Button bt_addBookmark;
	private Button bt_addCategory;
	private ListView lv_categories;
	private ListView lv_bookmarks;
	private Button bt_category;

	private SimpleCursorAdapter categoriesAdapter;
	private BookmarksCursorAdapter bookmarksAdapter;
	private Cursor categoriesCursor;
	private Cursor bookmarksCursor;
	private int currentCategory = 0;

	public BookmarksDialog(Activity activity, RFControlInterface rfControlInterface) {
		this.activity = activity;
		this.rfControlInterface = rfControlInterface;

		// Get references to the GUI components:
		ll_root = (LinearLayout) activity.getLayoutInflater().inflate(R.layout.bookmarks, null);
		bt_addBookmark = (Button) ll_root.findViewById(R.id.bt_bookmarks_addBookmark);
		bt_addCategory = (Button) ll_root.findViewById(R.id.bt_bookmarks_addCategory);
		fl_list = (FrameLayout) ll_root.findViewById(R.id.fl_bookmarks_list);
		lv_categories = (ListView) ll_root.findViewById(R.id.lv_bookmarks_categories);
		ll_bookmarks_list = (LinearLayout) ll_root.findViewById(R.id.ll_bookmarks_list);
		bt_category = (Button) ll_root.findViewById(R.id.bt_bookmarks_category);
		lv_bookmarks = (ListView) ll_root.findViewById(R.id.lv_bookmarks);

		// Set up all GUI components
		bt_addBookmark.setOnClickListener(this);
		bt_addCategory.setOnClickListener(this);
		bt_category.setOnClickListener(this);
		lv_categories.setOnItemClickListener(this);
		fl_list.bringChildToFront(lv_categories);
		ll_bookmarks_list.setVisibility(View.GONE);

		categoriesCursor = activity.getContentResolver().query(BookmarkCategories.CONTENT_URI, null, null, new String[] {}, null);

		// Set up the cursor adapter and long click listener for the categories
		categoriesAdapter = new SimpleCursorAdapter(activity, android.R.layout.simple_list_item_1,
				categoriesCursor,
				new String[]{BookmarkCategories.COLUMN_NAME_CATEGORY_NAME},
				new int[]{android.R.id.text1},
				0);
		lv_categories.setAdapter(categoriesAdapter);
		lv_categories.setLongClickable(true);
		lv_categories.setOnItemLongClickListener(this);

		// Set up the cursor adapter and long click listener for the bookmarks
		bookmarksAdapter = new BookmarksCursorAdapter(activity, bookmarksCursor);
		lv_bookmarks.setAdapter(bookmarksAdapter);
		lv_bookmarks.setLongClickable(true);
		lv_bookmarks.setOnItemLongClickListener(this);
		lv_bookmarks.setOnItemClickListener(this);

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
		dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
			@Override
			public void onDismiss(DialogInterface dialog) {
				if(bookmarksCursor != null)
					bookmarksCursor.close();
				if(categoriesCursor != null)
					categoriesCursor.close();
			}
		});
		dialog.show();
		dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
	}

	@Override
	public void onClick(View v) {
		if(v == bt_addBookmark) {
			if(categoriesCursor.getCount() > 0)
				new EditBookmarkDialog(activity, -1);
			else
				Toast.makeText(activity, "Please create a category first!", Toast.LENGTH_LONG).show();
		}

		else if(v == bt_addCategory) {
			new EditBookmarkCategoryDialog(activity, -1);
		}

		else if(v == bt_category) {
			// bring the category list to the front and hide the bookmarks layout:
			fl_list.bringChildToFront(lv_categories);
			lv_categories.setVisibility(View.VISIBLE);
			ll_bookmarks_list.setVisibility(View.GONE);
		}
	}

	@Override
	public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
		if(parent == lv_categories) {
			currentCategory = position;
			// Get the category name:
			categoriesCursor.moveToPosition(currentCategory);
			String categoryName = categoriesCursor.getString(categoriesCursor.getColumnIndex(BookmarkCategories.COLUMN_NAME_CATEGORY_NAME));
			bt_category.setText("◀ " + categoryName);

			// Query the db for the bookmarks:
			String selection = Bookmarks.COLUMN_NAME_CATEGORY_ID + " == ?";
			String[] selectionArgs = {"" + id};
			if(bookmarksCursor != null)
				bookmarksCursor.close();
			bookmarksCursor = activity.getContentResolver().query(Bookmarks.CONTENT_URI, null, selection, selectionArgs, null);
			bookmarksAdapter.changeCursor(bookmarksCursor);

			// bring the bookmarks layout to the front and hide the categories list:
			fl_list.bringChildToFront(ll_bookmarks_list);
			lv_categories.setVisibility(View.GONE);
			ll_bookmarks_list.setVisibility(View.VISIBLE);
		} else if(parent == lv_bookmarks) {
			bookmarksCursor.moveToPosition(position);
			long newChannelFrequency 	= bookmarksCursor.getLong(bookmarksCursor.getColumnIndex(Bookmarks.COLUMN_NAME_FREQUENCY));
			int newChannelWidth 		= bookmarksCursor.getInt(bookmarksCursor.getColumnIndex(Bookmarks.COLUMN_NAME_CHANNEL_WIDTH));
			int newMode 				= bookmarksCursor.getInt(bookmarksCursor.getColumnIndex(Bookmarks.COLUMN_NAME_MODE));
			int newSquelch		 		= bookmarksCursor.getInt(bookmarksCursor.getColumnIndex(Bookmarks.COLUMN_NAME_SQUELCH));

			// Set the new demodulation mode:
			int savedDemodulationMode = rfControlInterface.requestCurrentDemodulationMode();
			boolean ret = rfControlInterface.updateDemodulationMode(newMode);

			// Now check if we have to re-tune the source frequency:
			long currentFrequency 		= rfControlInterface.requestCurrentSourceFrequency();
			int currentSampleRate		= rfControlInterface.requestCurrentSampleRate();
			if (ret && ((newChannelFrequency - newChannelWidth / 2) < (currentFrequency - currentSampleRate / 2)
					|| (newChannelFrequency + newChannelWidth / 2) > (currentFrequency + currentSampleRate / 2))) {
				Log.d(LOGTAG, "onItemClick(): [bookmark] Re-tune source from " + currentFrequency
						+ " Hz to " + newChannelFrequency + " Hz.");
				// We use offset tuning (off by 1/8 * sampleRate)
				ret = rfControlInterface.updateSourceFrequency(newChannelFrequency - currentSampleRate/8);
			}

			if(ret)
				ret = rfControlInterface.updateChannelWidth(newChannelWidth);
			if (ret)
				ret = rfControlInterface.updateChannelFrequency(newChannelFrequency);
			if (ret)
				rfControlInterface.updateSquelch(newSquelch);
			if(ret) {
				// Close dialog
				dialog.dismiss();
			} else {
				// Show error
				Log.i(LOGTAG, "onItemClick(): Could not tune to bookmark frequency!");
				Toast.makeText(activity, "Cannot tune to bookmark frequency! Check that source is running and supports frequency.", Toast.LENGTH_LONG).show();

				// Restore previous demodulation mode:
				rfControlInterface.updateDemodulationMode(savedDemodulationMode);
			}

		} else {
			Log.e(LOGTAG, "onItemClick: Unknown parent: " + parent);
		}
	}

	@Override
	public boolean onItemLongClick(final AdapterView<?> parent, View view, int position, final long id) {
		// Creating a popup menu beside the long clicked view:
		PopupMenu popup = new PopupMenu(dialog.getContext(), view);
		popup.getMenuInflater().inflate(R.menu.bookmarks_menu, popup.getMenu());
		popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				if(item.getItemId() == R.id.bookmarks_edit) {
					if(parent==lv_categories)
						new EditBookmarkCategoryDialog(activity, id);
					else if(parent==lv_bookmarks)
						new EditBookmarkDialog(activity, id);
					else
						Log.e(LOGTAG, "onItemLongClick: Unknown parent list: " + parent);
				}
				else if(item.getItemId() == R.id.bookmarks_delete) {
					if(parent==lv_categories)
						deleteCategory(id);
					else if(parent==lv_bookmarks)
						deleteBookmark(id);
					else
						Log.e(LOGTAG, "onItemLongClick: Unknown parent list: " + parent);
				}
				return true;
			}
		});
		popup.show();
		return true;
	}

	public void deleteCategory(final long id) {
		// check if category is empty:
		Cursor cursor = activity.getContentResolver().query(Bookmarks.CONTENT_URI, null,
				Bookmarks.COLUMN_NAME_CATEGORY_ID + " == ?", new String[] {""+id}, null);
		if(cursor.getCount() > 0) {
			// show confirmation dialog:
			new AlertDialog.Builder(activity)
					.setTitle("Delete Category")
					.setMessage("Category contains " + cursor.getCount() + " Bookmarks that will be deleted too!")
					.setPositiveButton("Delete", new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							// Delete category (will cascade delete all contained bookmarks)
							activity.getContentResolver().delete(ContentUris.withAppendedId(BookmarkCategories.CONTENT_URI, id), null, null);
							reloadCategories();
						}
					})
					.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							// do nothing
						}
					})
					.create()
					.show();
		} else {
			// delete empty category:
			activity.getContentResolver().delete(ContentUris.withAppendedId(BookmarkCategories.CONTENT_URI, id), null, null);
			reloadCategories();
		}
		cursor.close();
	}

	public void deleteBookmark(long id) {
		// Delete bookmark:
		activity.getContentResolver().delete(ContentUris.withAppendedId(Bookmarks.CONTENT_URI, id), null, null);
		bookmarksCursor.requery();
	}

	public void reloadCategories() {
		if(categoriesCursor != null)
			categoriesCursor.close();
		categoriesCursor = activity.getContentResolver().query(BookmarkCategories.CONTENT_URI, null, null, new String[] {}, null);
		categoriesAdapter.swapCursor(categoriesCursor);
	}

	private class BookmarksCursorAdapter extends CursorAdapter {
		private final LayoutInflater layoutInflater;

		public BookmarksCursorAdapter(Context context, Cursor cursor) {
			super(context, cursor,false);	// no auto-requery
			layoutInflater = LayoutInflater.from(context);
		}

		@Override
		public View newView(Context context, Cursor cursor, ViewGroup parent) {
			return layoutInflater.inflate(R.layout.channel_list_item, null);
		}

		@Override
		public void bindView(View view, Context context, Cursor cursor) {
			int nameColumnIndex = cursor.getColumnIndex(Bookmarks.COLUMN_NAME_NAME);
			int frequencyColumnIndex = cursor.getColumnIndex(Bookmarks.COLUMN_NAME_FREQUENCY);
			int channelWidthColumnIndex = cursor.getColumnIndex(Bookmarks.COLUMN_NAME_CHANNEL_WIDTH);
			int modeColumnIndex = cursor.getColumnIndex(Bookmarks.COLUMN_NAME_MODE);
			String[] modes = context.getResources().getStringArray(R.array.demodulation_modes);
			String title = cursor.getString(nameColumnIndex);
			String details = "" + cursor.getLong(frequencyColumnIndex) + " Hz [" + (cursor.getInt(channelWidthColumnIndex)/1000)
					+ " kHz]: " + modes[cursor.getInt(modeColumnIndex)];
			((TextView) view.findViewById(R.id.tv_channelItem_title)).setText(title);
			((TextView) view.findViewById(R.id.tv_channelItem_details)).setText(details);
		}
	}

	public class EditBookmarkDialog {
		public EditBookmarkDialog(final Activity activity, final long bookmarkID) {
			// Get references to the GUI components:
			final LinearLayout ll_root = (LinearLayout) activity.getLayoutInflater().inflate(R.layout.edit_bookmark, null);
			final EditText et_name = (EditText) ll_root.findViewById(R.id.et_editBookmark_name);
			final Spinner sp_category = (Spinner) ll_root.findViewById(R.id.sp_editBookmark_category);
			final EditText et_frequency = (EditText) ll_root.findViewById(R.id.et_editBookmark_frequency);
			final EditText et_channelWidth = (EditText) ll_root.findViewById(R.id.et_editBookmark_channelWidth);
			final Spinner sp_mode = (Spinner) ll_root.findViewById(R.id.sp_editBookmark_mode);
			final EditText et_squelch = (EditText) ll_root.findViewById(R.id.et_editBookmark_squelch);
			final EditText et_comment = (EditText) ll_root.findViewById(R.id.et_editBookmark_comment);

			// Set up the spinners with adapters and values:
			final ArrayAdapter<CharSequence> modeAdapter = ArrayAdapter.createFromResource(activity, R.array.demodulation_modes, android.R.layout.simple_spinner_item);
			modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
			sp_mode.setAdapter(modeAdapter);
			final SimpleCursorAdapter categoryAdapter = new SimpleCursorAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
					categoriesCursor,
					new String[]{BookmarkCategories.COLUMN_NAME_CATEGORY_NAME},
					new int[]{android.R.id.text1},
					0);
			sp_category.setAdapter(categoryAdapter);

			// Query the bookmark if an ID was given:
			if(bookmarkID >= 0) {
				Cursor cursor = activity.getContentResolver().query(ContentUris.withAppendedId(Bookmarks.CONTENT_URI, bookmarkID), null, null, new String[] {}, null);
				cursor.moveToNext();
				et_name.setText(cursor.getString(cursor.getColumnIndex(Bookmarks.COLUMN_NAME_NAME)));
				et_frequency.setText("" + cursor.getLong(cursor.getColumnIndex(Bookmarks.COLUMN_NAME_FREQUENCY)));
				et_channelWidth.setText("" + cursor.getInt(cursor.getColumnIndex(Bookmarks.COLUMN_NAME_CHANNEL_WIDTH)));
				sp_mode.setSelection(cursor.getInt(cursor.getColumnIndex(Bookmarks.COLUMN_NAME_MODE)));
				et_squelch.setText("" + cursor.getFloat(cursor.getColumnIndex(Bookmarks.COLUMN_NAME_SQUELCH)));
				et_comment.setText(cursor.getString(cursor.getColumnIndex(Bookmarks.COLUMN_NAME_COMMENT)));

				// Setting the category is a bit nasty:
				int categoryPosition = getPositionOfId(categoriesCursor,
						cursor.getLong(cursor.getColumnIndex(Bookmarks.COLUMN_NAME_CATEGORY_ID)),
						BookmarkCategories._ID);
				if(categoryPosition >= 0)
					sp_category.setSelection(categoryPosition);
				cursor.close();
			}

			// Otherwise fill the fields with given default values (current demodulation settings):
			else {
				// Query current demodulation settings from the control interface:
				long frequency = rfControlInterface.requestCurrentChannelFrequency();
				int channelWidth = rfControlInterface.requestCurrentChannelWidth();
				int mode = rfControlInterface.requestCurrentDemodulationMode();
				float squelch = rfControlInterface.requestCurrentSquelch();

				// replace invalid values with defaults:
				if(frequency < 0)
					frequency = 100000000;
				if(channelWidth < 0)
					channelWidth = 0;
				if(Float.isNaN(squelch) || squelch < -100 || squelch > 10)
					squelch = -30;

				et_name.setText("-new bookmark-");
				et_frequency.setText("" + frequency);
				et_channelWidth.setText("" + channelWidth);
				sp_mode.setSelection(mode);
				et_squelch.setText("" + squelch);
				et_comment.setText("");

				sp_category.setSelection(currentCategory);
			}

			// Add listener to mode spinner to automatically correct channel width on changes:
			sp_mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
				@Override
				public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
					if(position > 0 && et_channelWidth.getText().length() > 0) {
						int channelWidth = Integer.valueOf(et_channelWidth.getText().toString());
						if(channelWidth < Demodulator.MIN_USER_FILTER_WIDTH[position])
							et_channelWidth.setText("" + Demodulator.MIN_USER_FILTER_WIDTH[position]);
						if(channelWidth > Demodulator.MAX_USER_FILTER_WIDTH[position])
							et_channelWidth.setText("" + Demodulator.MAX_USER_FILTER_WIDTH[position]);
					}
				}

				@Override
				public void onNothingSelected(AdapterView<?> parent) {

				}
			});

			// create and show dialog:
			final AlertDialog d = new AlertDialog.Builder(activity)
					.setTitle(bookmarkID<0 ? "Add Bookmark" : "Edit Bookmark")
					.setView(ll_root)
					.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							// do nothing
						}
					})
					.setPositiveButton(bookmarkID<0 ? "Add" : "Save", null) // set click listener later..
					.create();
			d.show();
			d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v)
				{
					String name = et_name.getText().toString();
					long categoryId = sp_category.getSelectedItemId();

					// Parse frequency
					if(et_frequency.getText().length() == 0) {
						Toast.makeText(activity, "Please specify a center frequency!", Toast.LENGTH_LONG).show();
						return;
					}
					double frequency = Double.valueOf(et_frequency.getText().toString());
					if (frequency < rfControlInterface.requestMaxSourceFrequency()/1000000)
						frequency = frequency * 1000000;

					// Parse channel width
					if(et_channelWidth.getText().length() == 0) {
						Toast.makeText(activity, "Please specify the channel width!", Toast.LENGTH_LONG).show();
						return;
					}
					int channelWidth = Integer.valueOf(et_channelWidth.getText().toString());
					int mode = sp_mode.getSelectedItemPosition();
					float squelch = Float.valueOf(et_squelch.getText().toString());
					String comment = et_comment.getText().toString();

					ContentValues values = new ContentValues();
					values.put(Bookmarks.COLUMN_NAME_NAME, name);
					values.put(Bookmarks.COLUMN_NAME_COMMENT, comment);
					values.put(Bookmarks.COLUMN_NAME_CATEGORY_ID, categoryId);
					values.put(Bookmarks.COLUMN_NAME_FREQUENCY, frequency);
					values.put(Bookmarks.COLUMN_NAME_CHANNEL_WIDTH, channelWidth);
					values.put(Bookmarks.COLUMN_NAME_MODE, mode);
					values.put(Bookmarks.COLUMN_NAME_SQUELCH, squelch);

					if(bookmarkID >= 0)
						activity.getContentResolver().update(ContentUris.withAppendedId(Bookmarks.CONTENT_URI,bookmarkID), values, null, null);
					else
						activity.getContentResolver().insert(Bookmarks.CONTENT_URI, values);

					if(bookmarksCursor != null)
						bookmarksCursor.requery();

					d.dismiss();
				}
			});

		}
	}

	public class EditBookmarkCategoryDialog {
		public EditBookmarkCategoryDialog(final Activity activity, final long categoryID) {
			// Get references to the GUI components:
			final LinearLayout ll_root = (LinearLayout) activity.getLayoutInflater().inflate(R.layout.edit_bookmark_category, null);
			final EditText et_name = (EditText) ll_root.findViewById(R.id.et_editBookmarkCategory_name);
			final EditText et_description = (EditText) ll_root.findViewById(R.id.et_editBookmarkCategory_description);

			// Query the category if an ID was given:
			if(categoryID >= 0) {
				Cursor cursor = activity.getContentResolver().query(ContentUris.withAppendedId(BookmarkCategories.CONTENT_URI, categoryID), null, null, new String[] {}, null);
				cursor.moveToNext();
				et_name.setText(cursor.getString(cursor.getColumnIndex(BookmarkCategories.COLUMN_NAME_CATEGORY_NAME)));
				et_description.setText(cursor.getString(cursor.getColumnIndex(BookmarkCategories.COLUMN_NAME_DESCRIPTION)));
				cursor.close();
			}

			// Otherwise fill the fields with default values:
			else {
				et_name.setText("-new category-");
				et_description.setText("This is a new category!");
			}

			// create and show dialog:
			new AlertDialog.Builder(activity)
					.setTitle(categoryID<0 ? "Add Category" : "Edit Category")
					.setView(ll_root)
					.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							// do nothing
						}
					})
					.setPositiveButton(categoryID<0 ? "Add" : "Save", new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							String name = et_name.getText().toString();
							String comment = et_description.getText().toString();

							ContentValues values = new ContentValues();
							values.put(BookmarkCategories.COLUMN_NAME_CATEGORY_NAME, name);
							values.put(BookmarkCategories.COLUMN_NAME_DESCRIPTION, comment);

							if(categoryID >= 0)
								activity.getContentResolver().update(ContentUris.withAppendedId(BookmarkCategories.CONTENT_URI,categoryID), values, null, null);
							else
								activity.getContentResolver().insert(BookmarkCategories.CONTENT_URI, values);

							reloadCategories();
						}
					})
					.create()
					.show();
		}
	}

	/**
	 * Will loop through the cursor to find the position of the element with the given ID
	 * @param cursor		cursor
	 * @param id			id to look for
	 * @param idColumnName	name of the ID column of the cursor (most likely "_ID")
	 * @return position of the item with the given ID or -1 if not found
	 */
	public static int getPositionOfId(Cursor cursor, long id, String idColumnName) {
		int columnIndex = cursor.getColumnIndex(idColumnName);
		for(int i = 0; i < cursor.getCount(); i++) {
			cursor.moveToPosition(i);
			if(cursor.getLong(columnIndex) == id)
				return i;
		}
		return -1;
	}
}
