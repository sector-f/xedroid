package com.novaember.xedroid;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import android.widget.ProgressBar;

public class Xedule
{
    private static int cacheTimeout = 60; // in seconds
    private static boolean cacheEnabled = false;
    private static String apiSite = "http://xedule.novaember.com/";

    public static JSONArray getArray(String location) throws JSONException
    {
        return new JSONArray(get(location));
    }

    public static JSONObject getObject(String location) throws JSONException
    {
        return new JSONObject(get(location));
    }

    private static String get(String location)
    {
        File cacheFile = new File(Xedroid.getContext().getCacheDir(), location);
        String output = null;

        if (cacheEnabled && cacheFile.exists()) try
        {
            BufferedReader cacheReader = new BufferedReader(new FileReader(cacheFile));
            StringBuilder sb = new StringBuilder();

            String line;
            while ((line = cacheReader.readLine()) != null) sb.append(line + "\n");

            cacheReader.close();

            if (sb.length() > 0)
            {
                output = sb.toString();
            }
        }
        catch(Exception e) {Log.d("Xedule", "aa", e);} // Do nothing

        if (output == null) try
        {
            output = Fetcher.downloadUrl(apiSite + location);

            FileWriter cacheWriter = new FileWriter(cacheFile);
            cacheWriter.write(output);
            cacheWriter.close();
        }
        catch (IOException e)
        {
            Log.w("Xedule", "Could not write cache file", e);
        }

        return output;
    }

    public static void updateOrganisations()
    {
        SQLiteDatabase db = new DatabaseOpenHelper(Xedroid.getContext()).getWritableDatabase();
        db.beginTransaction();

        try
        {
            JSONArray organisationsJSONArray = Xedule.getArray("organisations.json");

            for (int i = 0; i < organisationsJSONArray.length(); i++)
            {
                JSONObject obj = organisationsJSONArray.getJSONObject(i);

                new Organisation(obj.getInt("id"), obj.getString("name")).save(db);
            }

            db.setTransactionSuccessful();
        }
        catch(JSONException e)
        {
            Log.e("Xedule", "Couldn't update organisations", e);
        }
        finally
        {
            db.endTransaction();
        }
    }

    public static void updateLocations(int organisation)
    {
        SQLiteDatabase db = new DatabaseOpenHelper(Xedroid.getContext()).getWritableDatabase();
        db.beginTransaction();

        try
        {
            JSONArray locationsJSONArray = Xedule.getArray("locations." + organisation + ".json");

            for (int i = 0; i < locationsJSONArray.length(); i++)
            {
                JSONObject obj = locationsJSONArray.getJSONObject(i);
                JSONArray weeksJSONArray = obj.getJSONArray("weeks");
                ArrayList<String> weeks = new ArrayList<String>();

                for (int j = 0; j < weeksJSONArray.length(); j++)
                {
                    weeks.add(weeksJSONArray.getString(j));
                }

                new Location(obj.getInt("id"), obj.getString("name"), organisation, weeks.toArray(new String[weeks.size()])).save(db);
            }

            db.setTransactionSuccessful();
        }
        catch(JSONException e)
        {
            Log.e("Xedule", "Couldn't update locations for organisation #" + organisation, e);
        }
        finally
        {
            db.endTransaction();
        }
    }

    public static void updateAttendees(int location, ProgressBar progressBar)
    {
        SQLiteDatabase db = new DatabaseOpenHelper(Xedroid.getContext()).getWritableDatabase();
        db.beginTransaction();

        try
        {
            JSONArray attendeesJSONArray = Xedule.getArray("attendees." + location + ".json");

            if (progressBar != null)
            {
                progressBar.setIndeterminate(false);
                progressBar.setMax(attendeesJSONArray.length());
            }

            for (int i = 0; i < attendeesJSONArray.length(); i++)
            {
                JSONObject obj = attendeesJSONArray.getJSONObject(i);

                new Attendee(obj.getInt("id"), obj.getString("name"), location, obj.getInt("type")).save(db);

                if (progressBar != null) progressBar.setProgress(i);
            }

            db.setTransactionSuccessful();
        }
        catch(JSONException e)
        {
            Log.e("Xedule", "Couldn't update attendees for location #" + location, e);
        }
        finally
        {
            db.endTransaction();
        }
    }

    public static void updateEvents(int attendee, int year, int week)
    {
        SQLiteDatabase db = new DatabaseOpenHelper(Xedroid.getContext()).getWritableDatabase();
        db.beginTransaction();

        db.delete("attendee_events_view", "attendee = ? AND year = ? AND week = ?",
                new String[]{ String.valueOf(attendee), String.valueOf(year), String.valueOf(week) });

        try
        {
            JSONArray daysJSONArray = Xedule.getArray("weekschedule." + attendee + ".json?year=" + year + "&week=" + week);

            Event event;

            for (int i = 0; i < daysJSONArray.length(); i++)
            {
                if (daysJSONArray.isNull(i)) continue;

                JSONObject dayJSONObject = daysJSONArray.getJSONObject(i);
                JSONArray eventsJSONArray = dayJSONObject.getJSONArray("events");

                int day = "Mon Tue Wed Thu Fri Sat Sun".indexOf(dayJSONObject.getString("date").substring(0, 3)) / 4 + 1;

                for (int j = 0; j < eventsJSONArray.length(); j++)
                {
                    JSONObject eventJSONObject = eventsJSONArray.getJSONObject(j);

                    event = new Event(year, week, day,
                            new Event.Time(eventJSONObject.getString("start")), new Event.Time(eventJSONObject.getString("end")),
                            eventJSONObject.getString("description"));

                    JSONArray attendees = eventJSONObject.getJSONArray("attendees");

                    for (int k = 0; k < attendees.length(); k++)
                    {
                        event.addAttendee(new Attendee(attendees.getInt(k)));
                    }

                    event.save(db);
                }
            }

            ContentValues values = new ContentValues();
            values.put("attendee", attendee);
            values.put("year", year);
            values.put("week", week);
            values.put("lastUpdate", System.currentTimeMillis() / 1000L);
            db.insertWithOnConflict("weekschedule_age", null, values, SQLiteDatabase.CONFLICT_REPLACE);

            db.setTransactionSuccessful();
        }
        catch(JSONException e)
        {
            Log.e("Xedule", "Couldn't update events for attendee #" + attendee, e);
        }
        finally
        {
            db.endTransaction();
        }
    }
}