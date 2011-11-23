package com.gtosoft.voydemo;

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;

import com.artfulbits.aiCharts.ChartView;

public class ChartAdapter extends ArrayAdapter<ChartView> {
	int resource;
	Context mctxParent;
	
	List<ChartView> chartViews;

	public ChartAdapter(Context context, int textViewResourceId, List<ChartView> objects) {
		super(context, textViewResourceId, objects);
		
		chartViews = objects;
		
		resource = textViewResourceId;
		mctxParent = context;
	}

	
	@Override
	public View getView(int position, View convertView, ViewGroup parent) {

		LinearLayout chartviewlayout;
		
		if (convertView == null) {
			// instantiate a new layout for the list
			msg ("getting new linearlayout");
			chartviewlayout = new LinearLayout(getContext());
			String inflater = Context.LAYOUT_INFLATER_SERVICE;
			LayoutInflater vi = (LayoutInflater) getContext().getSystemService(inflater);

			vi.inflate(resource, chartviewlayout, true);
			
			ChartView c = new ChartView(mctxParent, R.xml.shortchart);
			chartViews.add(c);
			chartviewlayout.addView(c);

		} else{

			// re-use existing list item. 
			chartviewlayout = (LinearLayout) convertView;
		}
		
		return chartviewlayout;
		
	}// end of getview()
	
	
	private void msg (String m) {
		Log.d("ChartAdapter",m);
	}

}// end of class.
