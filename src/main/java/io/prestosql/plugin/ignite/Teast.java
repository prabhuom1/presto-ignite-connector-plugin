package io.prestosql.plugin.ignite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class Teast {
	public static void main(String[] args) {
		List<String> pkColumn=new ArrayList<String>();
    	Map<String, String> columnDetails=new HashMap<String,String>();
    	
    	StringBuilder primaryKeyString=new StringBuilder("PRIMARY KEY (");
    	StringBuilder columnDetailsString=new StringBuilder();
    	
    	pkColumn.add("ID");
    	pkColumn.add("city_id");
    	
    	columnDetails.put("ID","varchar");
    	columnDetails.put("city_id","varchar");
    	
    	for (String pk : pkColumn) {
    		primaryKeyString.append(pk).append(",");
		}
    	for (Entry<String, String> ent : columnDetails.entrySet()) {
    		columnDetailsString.append(ent.getKey()).append(" ").append(ent.getValue()).append(",");
		}
    	
    	
    	primaryKeyString.replace(primaryKeyString.lastIndexOf(","),primaryKeyString.length(),")");
    	System.out.println(primaryKeyString.toString());
    	
    	System.out.println(columnDetailsString.append(primaryKeyString.toString()));
	    
	}

}
