package com.mantz_it.rfanalyzer;

import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.test.ProviderTestCase2;
import android.test.mock.MockContentResolver;

import com.mantz_it.rfanalyzer.BookmarksContract.BookmarkCategories;
import com.mantz_it.rfanalyzer.BookmarksContract.Bookmarks;
/**
 * Created by dennis on 01/04/15.
 */
public class ContentProviderTest extends ProviderTestCase2<BookmarksProvider> {

	private MockContentResolver resolver;

	public ContentProviderTest() {
		super(BookmarksProvider.class, "com.mantz_it.rfanalyzer.provider");
	}

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		resolver = getMockContentResolver();
	}

	public void test_01_verifyEmptyDB() {
		Cursor cursor = resolver.query(BookmarkCategories.CONTENT_URI, null, null, new String[] {}, null);
		assertNotNull(cursor);
		assertEquals(cursor.getCount(), 0);

		cursor = resolver.query(Bookmarks.CONTENT_URI, null, null, new String[] {}, null);
		assertNotNull(cursor);
		assertEquals(cursor.getCount(), 0);
	}

	public void test_02_insertQueryUpdateDeleteRows() {
		// Inserting a category:
		ContentValues fmRadioCategory = new ContentValues();
		fmRadioCategory.put(BookmarkCategories.COLUMN_NAME_CATEGORY_NAME, "FM Radio");
		fmRadioCategory.put(BookmarkCategories.COLUMN_NAME_DESCRIPTION, "Some public FM Radio stations in the range 88 - 108 MHz");

		Uri fmRadioCategoryUri = resolver.insert(BookmarkCategories.CONTENT_URI, fmRadioCategory);
		assertNotNull(fmRadioCategoryUri);
		System.out.println("FM Radio category URI: " + fmRadioCategoryUri.toString());
		System.out.println("FM Radio category ID: " + ContentUris.parseId(fmRadioCategoryUri));

		// Inserting a bookmark:
		ContentValues bookmark1 = new ContentValues();
		bookmark1.put(Bookmarks.COLUMN_NAME_NAME, "SWR3");
		bookmark1.put(Bookmarks.COLUMN_NAME_COMMENT, "very strong signal");
		bookmark1.put(Bookmarks.COLUMN_NAME_CATEGORY_ID, ContentUris.parseId(fmRadioCategoryUri));
		bookmark1.put(Bookmarks.COLUMN_NAME_FREQUENCY, 103000000l);
		bookmark1.put(Bookmarks.COLUMN_NAME_CHANNEL_WIDTH, 100000);
		bookmark1.put(Bookmarks.COLUMN_NAME_MODE, Demodulator.DEMODULATION_WFM);
		bookmark1.put(Bookmarks.COLUMN_NAME_SQUELCH, -40);

		Uri bookmark1Uri = resolver.insert(Bookmarks.CONTENT_URI, bookmark1);
		assertNotNull(bookmark1Uri);
		System.out.println("Bookmark 1 URI: " + bookmark1Uri.toString());
		System.out.println("Bookmark 1 ID: " + ContentUris.parseId(bookmark1Uri));

		// Inserting a second bookmark:
		ContentValues bookmark2 = new ContentValues();
		bookmark2.put(Bookmarks.COLUMN_NAME_NAME, "DasDing!");
		bookmark2.put(Bookmarks.COLUMN_NAME_COMMENT, "very cool station");
		bookmark2.put(Bookmarks.COLUMN_NAME_CATEGORY_ID, ContentUris.parseId(fmRadioCategoryUri));
		bookmark2.put(Bookmarks.COLUMN_NAME_FREQUENCY, 95000000l);
		bookmark2.put(Bookmarks.COLUMN_NAME_CHANNEL_WIDTH, 100000);
		bookmark2.put(Bookmarks.COLUMN_NAME_MODE, Demodulator.DEMODULATION_WFM);
		bookmark2.put(Bookmarks.COLUMN_NAME_SQUELCH, -50);

		Uri bookmark2Uri = resolver.insert(Bookmarks.CONTENT_URI, bookmark2);
		assertNotNull(bookmark2Uri);
		System.out.println("Bookmark 2 URI: " + bookmark2Uri.toString());
		System.out.println("Bookmark 2 ID: " + ContentUris.parseId(bookmark2Uri));

		// query all bookmarks:
		Cursor cursor = resolver.query(Bookmarks.CONTENT_URI, null, null, null, null);
		assertNotNull(cursor);
		assertEquals(cursor.getCount(), 2);
		int columnIndexOfName = cursor.getColumnIndex(Bookmarks.COLUMN_NAME_NAME);
		System.out.println("Column index of name: " + columnIndexOfName);
		cursor.moveToNext();
		System.out.println("Queried bookmarks: " + cursor.getString(columnIndexOfName));
		cursor.moveToNext();
		System.out.println("Queried bookmarks: " + cursor.getString(columnIndexOfName));

		// query only the second bookmark:
		cursor = resolver.query(bookmark2Uri, null, null, null, null);
		assertNotNull(cursor);
		assertEquals(cursor.getCount(), 1);
		cursor.moveToNext();
		assertEquals(cursor.getString(columnIndexOfName), "DasDing!");

		// update the first bookmark:
		ContentValues bookmark1Updated = new ContentValues();
		bookmark1Updated.put(Bookmarks.COLUMN_NAME_COMMENT, "This is an updated comment!");
		int returnValue = resolver.update(bookmark1Uri, bookmark1Updated, null, null);
		assertEquals(returnValue, 1);

		// check results:
		cursor = resolver.query(bookmark1Uri, null, null, null, null);
		assertNotNull(cursor);
		assertEquals(cursor.getCount(), 1);
		cursor.moveToNext();
		int columnIndexOfComment = cursor.getColumnIndex(Bookmarks.COLUMN_NAME_COMMENT);
		assertEquals(cursor.getString(columnIndexOfComment), "This is an updated comment!");

		// delete the second bookmark
		returnValue = resolver.delete(bookmark2Uri, null, null);
		assertEquals(returnValue, 1);
		cursor = resolver.query(bookmark2Uri, null, null, null, null);
		assertNotNull(cursor);
		assertEquals(cursor.getCount(), 0);

		// delete the category (will also delete the first bookmark)
		returnValue = resolver.delete(fmRadioCategoryUri, null, null);
		assertEquals(returnValue, 1);
		cursor = resolver.query(Bookmarks.CONTENT_URI, null, null, null, null);
		assertNotNull(cursor);
		assertEquals(cursor.getCount(), 0);
	}
}
