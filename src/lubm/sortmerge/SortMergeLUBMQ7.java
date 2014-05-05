package lubm.sortmerge;

/**
 * Sort Merge Join LUBM Q7
 * @date May 2014
 * @author Albert Haque
 */

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

import bsbm.sortmerge.KeyValueArrayWritable;
import bsbm.sortmerge.SharedServices;


public class SortMergeLUBMQ7 {
	
	// Begin Query Information
	
	// End Query Information
		
	public static void main(String[] args) throws ClassNotFoundException, IOException, InterruptedException {

		// Zookeeper quorum is usually the same as the HBase master node
		String USAGE_MSG = "Arguments: <table name> <zookeeper quorum>";

		if (args == null || args.length != 2) {
			System.out.println("\n  You entered " + args.length + " arguments.");
			System.out.println("  " + USAGE_MSG);
			System.exit(0);
		}
		startJob(args);
	}
	
	public static Job startJob(String[] args) throws IOException {
		
		// args[0] = hbase table name
		// args[1] = zookeeper
		
		Configuration hConf = HBaseConfiguration.create(new Configuration());
	    hConf.set("hbase.zookeeper.quorum", args[1]);
	    hConf.set("scan.table", args[0]);
	    hConf.set("hbase.zookeeper.property.clientPort", "2181");

	    Scan scan = new Scan();
	    //scan.setFilter(rowColBloomFilter());
		
		Job job = new Job(hConf);
		job.setJobName("LUBM-Q7-SortMerge");
		job.setJarByClass(SortMergeLUBMQ2.class);
		// Change caching to speed up the scan
		scan.setCaching(500);        
		scan.setCacheBlocks(false);
		
		// Mapper settings
		TableMapReduceUtil.initTableMapperJob(
				args[0],        // input HBase table name
				scan,             // Scan instance to control CF and attribute selection
				SortMergeMapper.class,   // mapper
				Text.class,         // mapper output key
				KeyValueArrayWritable.class,  // mapper output value
				job);

		// Reducer settings
		//job.setReducerClass(SortMergeReducer.class);    // reducer class
		job.setReducerClass(SharedServices.ReduceSideJoin_Reducer.class);
		//job.setNumReduceTasks(1);    // at least one, adjust as required
	
		FileOutputFormat.setOutputPath(job, new Path("output/LUBMQ7"));

		try {
			System.exit(job.waitForCompletion(true) ? 0 : 1);
		} catch (ClassNotFoundException e) { e.printStackTrace(); }
		  catch (InterruptedException e) { e.printStackTrace();}

		return job;
	}
	
	
	public static class SortMergeMapper extends TableMapper<Text, KeyValueArrayWritable> {
		
		public void map(ImmutableBytesWritable row, Result value, Context context) throws InterruptedException, IOException {
		/* LUBM QUERY 7
		   ----------------------------------------
			SELECT ?X, ?Y
			WHERE 
			{
			[TP-01] ?X rdf:type ub:Student .
			[TP-02] ?Y rdf:type ub:Course .
			[TP-03] ?X ub:takesCourse ?Y .
			[TP-04] <http://www.Department0.University0.edu/AssociateProfessor0> ub:teacherOf ?Y
			}
		   ---------------------------------------
		 */
	
			List<KeyValue> rowData = value.list();
			List<KeyValue> toTransmit = new ArrayList<KeyValue>();
			for (KeyValue kv : rowData) {
				// TP-01
				if (Arrays.equals(kv.getQualifier(), "ub_Student".getBytes())) {
					if (!Arrays.equals(kv.getValue(), "rdf_type".getBytes())) {
						return;
					}
				} else if (Arrays.equals(kv.getValue(), "ub_takesCourse".getBytes())) {
					toTransmit.add(kv);
					context.write(new Text(kv.getQualifier()), new KeyValueArrayWritable(SharedServices.listToArray(toTransmit)));
				}
			}
		}
	}
	
	// Output format:
	// Key: HBase Row Key (subject)
	// Value: All projected attributes for the row key (subject)
	public static class SortMergeReducer extends Reducer<Text, KeyValueArrayWritable, Text, Text> {

		HTable table;
		@Override
		protected void setup(Context context) throws IOException,
				InterruptedException {
			Configuration conf = context.getConfiguration();
			table = new HTable(conf, conf.get("scan.table"));
		}

		public void reduce(Text key, Iterable<KeyValueArrayWritable> values,
				Context context) throws IOException, InterruptedException {
			/* LUBM QUERY 7
			   ----------------------------------------
			SELECT ?X, ?Y
			WHERE 
			{
			[TP-01] ?X rdf:type ub:Student .
			[TP-02] ?Y rdf:type ub:Course .
			[TP-03] ?X ub:takesCourse ?Y .
			[TP-04] <http://www.Department0.University0.edu/AssociateProfessor0> ub:teacherOf ?Y
			}
			   ---------------------------------------
			 */

			List<KeyValue> finalKeyValues = new ArrayList<KeyValue>();

			KeyValue kv_university = null;
			KeyValue kv_department = null;
			for (KeyValueArrayWritable array : values) {
				for (KeyValue kv : (KeyValue[]) array.toArray()) {
					if (Arrays.equals(kv.getValue(), "ub_undergraduateDegreeFrom".getBytes())) {
						kv_university = kv;
						finalKeyValues.add(kv);
					} else if (Arrays.equals(kv.getValue(), "ub_memberOf".getBytes())) {
						kv_department = kv;
						finalKeyValues.add(kv);
					}
				}
			}
			if (kv_university == null) {
				return;
			}
			// TP-02
			Get g = new Get(kv_university.getQualifier());
			g.addColumn(SharedServices.CF_AS_BYTES, "ub_University".getBytes());
			Result universityResult = table.get(g);
			byte[] universityPredicate = universityResult.getValue(SharedServices.CF_AS_BYTES,"ub_University".getBytes());
			if (!Arrays.equals(universityPredicate, "rdf_type".getBytes())) {
				return;
			}
			
			// Find and get department information
			// TP-04
			if (kv_department == null) {
				return;
			}
			// TP-03
			Result departmentResult = table.get(new Get(kv_department.getQualifier()));
			// TP-05
			for (KeyValue kv : departmentResult.list()) {
				if (Arrays.equals(kv.getValue(), "ub_subOrganizationOf".getBytes())) {
					if (!Arrays.equals(kv.getQualifier(), kv_university.getQualifier())) {
						return;
					}
				}
			}

			// Format and output the values
			StringBuilder builder = new StringBuilder();
			builder.append("\n");
			for (KeyValue kv : finalKeyValues) {
				String[] triple = null;
				try {
					triple = SharedServices.keyValueToTripleString(kv);
				} catch (ClassNotFoundException e) {
					e.printStackTrace();
				}
				builder.append("\t" + triple[1] + "\t" + triple[2] + "\n");
			}
			context.write(key, new Text(builder.toString()));
		}
	}
}
