package com.gtosoft.voydemo;

import java.util.List;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;

import com.artfulbits.aiCharts.ChartView;

public class ChartAdapter extends ArrayAdapter<ChartView> {
	int resource;

	public ChartAdapter(Context context, int textViewResourceId, List objects) {
		super(context, textViewResourceId, objects);
		
		resource = textViewResourceId;
	}

	
	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
//		super.getView(position, convertView, parent);
		
//		ChartView cv = getItem(position);
		LinearLayout chartviewlayout;
		
		if (convertView == null) {
			// instantiate a new layout for the list
			msg ("getting new linearlayout");
			chartviewlayout = new LinearLayout(getContext());
			String inflater = Context.LAYOUT_INFLATER_SERVICE;
			LayoutInflater vi = (LayoutInflater) getContext().getSystemService(inflater);
//			msg ("inflating...");
			vi.inflate(resource, chartviewlayout, true);
			
		} else{
//			msg ("re-using a view");
			// re-use existing list item. 
			chartviewlayout = (LinearLayout) convertView;
		}
		
		
		// ChartView c = (ChartView) chartviewlayout.getChildAt(2);
		// TODO: bring this chart to life. 
		
		
		return chartviewlayout;
		
	}// end of getview()
	
	
	private void msg (String m) {
		Log.d("ChartAdapter",m);
	}

}// end of class.
