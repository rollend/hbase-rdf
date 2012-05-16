package nl.vu.datalayer.hbase.bulkload;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import nl.vu.datalayer.hbase.HBaseClientSolution;
import nl.vu.datalayer.hbase.HBaseConnection;
import nl.vu.datalayer.hbase.HBaseFactory;
import nl.vu.datalayer.hbase.id.BaseId;
import nl.vu.datalayer.hbase.id.DataPair;
import nl.vu.datalayer.hbase.id.TypedId;
import nl.vu.datalayer.hbase.schema.HBPrefixMatchSchema;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.HFileOutputFormat;
import org.apache.hadoop.hbase.mapreduce.LoadIncrementalHFiles;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.Counters;
import org.apache.hadoop.mapred.Counters.Counter;
import org.apache.hadoop.mapred.Counters.Group;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;


/**
 * Class representing main entry point for Bulk loading process
 * 
 */

public class BulkLoad {
	
	//public static final String INPUT_PATH = "/user/sfu200/input/dbpedia";
	//public static final String OUTPUT_PATH = "/user/sfu200/output";
	
	/**
	 * Used to load successively the tables containing quads
	 */
	private static HTable currentTable = null;
	
	private static HTable string2Id = null;
	private static HTable id2String = null;
	
	/**
	 * Cluster parameters used to estimate number of reducers 
	 */
	public static int CLUSTER_SIZE = 32;
	public static int TASK_PER_NODE = 2;
	
	
	/**
	 * Estimate of a quad size  
	 */
	public static int QUAD_AVERAGE_SIZE = 230;
	public static int ELEMENTS_PER_QUAD = 4;
	
	
	/**
	 * Estimate of imbalance of data  distributed across reducers
	 * 1.0 = data equally distributed across reducers 
	 */
	public static double LOAD_BALANCER_FACTOR = 1.2;
	
	public static SortedMap<Short, Long> sufixCounters;
	
	public static long totalStringCount, numericalCount, literalCount, bNodeCount;
	public static int tripleToResourceReduceTasks;
	
	public static JobConf createTripleToResourceJob(Path input, Path output, int inputSizeEstimate) throws IOException{
			
		JobConf conf = new JobConf();
		conf.setJobName("TripleToResource");
		conf.setInt("mapred.job.reuse.jvm.num.tasks", -1);
		conf.setFloat("io.sort.record.percent", 0.6f);
		conf.setFloat("io.sort.spill.percent", 0.6f);
		
		long numInputBytes = (long)(inputSizeEstimate*Math.pow(10.0, 6.0));
		long numQuads = numInputBytes/QUAD_AVERAGE_SIZE;
		long totalNumberOfElements = numQuads*ELEMENTS_PER_QUAD;
		
		tripleToResourceReduceTasks = (int)(Math.ceil((double)totalNumberOfElements/Math.pow(2.0, 24.0)) * LOAD_BALANCER_FACTOR);
		System.out.println("Number of reduce tasks: "+tripleToResourceReduceTasks);
		conf.setNumReduceTasks(tripleToResourceReduceTasks);
		//conf.setNumReduceTasks(3);
		
		conf.setJarByClass(BulkLoad.class);
		conf.setMapperClass(TripleToResource.TripleToResourceMapper.class);
		conf.setReducerClass(TripleToResource.TripleToResourceReducer.class);
		
		conf.setOutputKeyClass(TypedId.class);
		conf.setOutputValueClass(DataPair.class);
		
		conf.setMapOutputKeyClass(Text.class);
		conf.setMapOutputValueClass(DataPair.class);
		
		conf.setInputFormat(TextInputFormat.class);
		conf.setOutputFormat(SequenceFileOutputFormat.class);
		TextInputFormat.setInputPaths(conf, input);
		SequenceFileOutputFormat.setOutputPath(conf, output);
		
		return conf;
	}
	
	public static JobConf createResourceToTripleJob(Path input, Path output) throws IOException {

		JobConf conf = new JobConf();
		
		conf.setJobName("ResourceToTriple");
		conf.setInt("mapred.job.reuse.jvm.num.tasks", -1);
		conf.setFloat("io.sort.record.percent", 0.2f);
		conf.setFloat("io.sort.spill.percent", 0.7f);
		int reduceTasks = (int)(1.75*(double)CLUSTER_SIZE*(double)TASK_PER_NODE);
		conf.setNumReduceTasks(reduceTasks);

		conf.setJarByClass(BulkLoad.class);
		conf.setMapperClass(ResourceToTriple.ResourceToTripleMapper.class);
		conf.setReducerClass(ResourceToTriple.ResourceToTripleReducer.class);

		conf.setMapOutputKeyClass(BaseId.class);
		conf.setMapOutputValueClass(DataPair.class);
		conf.setOutputKeyClass(NullWritable.class);
		conf.setOutputValueClass(ImmutableBytesWritable.class);
		conf.setInputFormat(org.apache.hadoop.mapred.SequenceFileInputFormat.class);
		conf.setOutputFormat(SequenceFileOutputFormat.class);
		org.apache.hadoop.mapred.SequenceFileInputFormat.setInputPaths(conf, input);
		SequenceFileOutputFormat.setOutputPath(conf, output);

		return conf;
	}
	
	public static Job createString2IdJob(HBaseConnection con, Path input, Path output) throws Exception {

		JobConf conf = new JobConf();
		conf.setInt("mapred.job.reuse.jvm.num.tasks", -1);
		conf.setFloat("io.sort.record.percent", 0.6f);//intermediate keys are usually bigger than intermediate values 
		conf.setFloat("io.sort.spill.percent", 0.6f);
		Job j = new Job(conf);

		j.setJobName(HBPrefixMatchSchema.STRING2ID);
		j.setJarByClass(BulkLoad.class);
		j.setMapperClass(StringIdAssoc.String2IdMapper.class);
		j.setMapOutputKeyClass(ImmutableBytesWritable.class);
		j.setMapOutputValueClass(Put.class);
		j.setInputFormatClass(SequenceFileInputFormat.class);
		j.setOutputFormatClass(HFileOutputFormat.class);

		SequenceFileInputFormat.setInputPaths(j, input);
		HFileOutputFormat.setOutputPath(j, output);

		string2Id = new HTable(con.getConfiguration(), HBPrefixMatchSchema.STRING2ID);

		HFileOutputFormat.configureIncrementalLoad(j, string2Id);

		return j;
	}
	
	public static Job createId2StringJob(HBaseConnection con, Path input, Path output) throws Exception {

		Job j = new Job();

		j.setJobName(HBPrefixMatchSchema.ID2STRING);
		j.setJarByClass(BulkLoad.class);
		j.setMapperClass(StringIdAssoc.Id2StringMapper.class);
		j.setMapOutputKeyClass(ImmutableBytesWritable.class);
		j.setMapOutputValueClass(Put.class);
		j.setInputFormatClass(SequenceFileInputFormat.class);
		j.setOutputFormatClass(HFileOutputFormat.class);

		SequenceFileInputFormat.setInputPaths(j, input);
		HFileOutputFormat.setOutputPath(j, output);

		id2String = new HTable(con.getConfiguration(), HBPrefixMatchSchema.ID2STRING);

		HFileOutputFormat.configureIncrementalLoad(j, id2String);

		return j;
	}
	
	public static Job createPrefixMatchJob(HBaseConnection con, Path input, Path output, int tableIndex, Class<? extends Mapper> cls) throws Exception {		
		Configuration conf = new Configuration();
		conf.setFloat("io.sort.record.percent", 0.3f);
		Job j = new Job(conf);

		j.setJobName(HBPrefixMatchSchema.TABLE_NAMES[tableIndex]);
		j.setJarByClass(BulkLoad.class);
		j.setMapperClass(cls);
		j.setMapOutputKeyClass(ImmutableBytesWritable.class);
		j.setMapOutputValueClass(Put.class);
		j.setInputFormatClass(SequenceFileInputFormat.class);
		j.setOutputFormatClass(HFileOutputFormat.class);

		SequenceFileInputFormat.setInputPaths(j, input);
		HFileOutputFormat.setOutputPath(j, output);

		currentTable = new HTable(con.getConfiguration(), HBPrefixMatchSchema.TABLE_NAMES[tableIndex]);

		HFileOutputFormat.configureIncrementalLoad(j, currentTable);

		return j;
	}
	
	public static void retrieveCounters(RunningJob runningJ1) throws IOException{
		sufixCounters = new TreeMap<Short, Long>();
		
		Counters counters = runningJ1.getCounters();
		Group numGroup = counters.getGroup(TripleToResource.TripleToResourceReducer.NUMERICAL_GROUP);
		totalStringCount = numGroup.getCounter("NonNumericals");
		numericalCount = numGroup.getCounter("Numericals");
		
		Group elemsGroup = counters.getGroup(TripleToResource.TripleToResourceReducer.ELEMENT_TYPE_GROUP);
		literalCount = elemsGroup.getCounter("Literals");
		//long nonNumericalLiteralCount = literalCount-numericalCount;
		bNodeCount = elemsGroup.getCounter("Blanks");
		
		//build histogram
		Group histogramGroup = counters.getGroup(TripleToResource.TripleToResourceReducer.HISTOGRAM_GROUP);
		Iterator<Counter> it = histogramGroup.iterator();
		while (it.hasNext()){
			Counter c = it.next();
			short b = Short.parseShort(c.getName(), 16);
			System.out.println((char)b +" "+c.getName()+" "+c.getCounter());
			
			long half = c.getCounter()/2;
			sufixCounters.put(b, half);
			sufixCounters.put((short)(b | 0x01), c.getCounter()-half);
		}		
		
		//save counter values to file
		FileWriter file = new FileWriter("Counters");
		file.write(Long.toString(totalStringCount)+"\n");
		file.write(Long.toString(numericalCount)+"\n");
		file.write(Integer.toString(tripleToResourceReduceTasks)+"\n");
		
		for (Map.Entry<Short, Long> entry : sufixCounters.entrySet()) {
			file.write(entry.getKey()+"@"+entry.getValue()+"\n");
		}
		file.close();
	}
	
	public static void tempCounters(){
		totalStringCount = 193426919L;
		numericalCount = 22694894L;
	}
	
	public static void buildCountersFromFile() throws IOException{
		sufixCounters = new TreeMap();
		FileReader file2 = new FileReader("Counters");
		BufferedReader reader = new BufferedReader(file2);
		totalStringCount = Long.parseLong(reader.readLine());
		numericalCount = Long.parseLong(reader.readLine());
		tripleToResourceReduceTasks = Integer.parseInt(reader.readLine());
		
		String line;
		long totalCounter = 0;
		while ((line=reader.readLine()) != null){
			String []tokens  = line.split("@");
			short s = Short.parseShort(tokens[0]);
			long current = Long.parseLong(tokens[1]);
			System.out.println("Short: "+s+"; Character: "+(char)s+"; "+current);
			sufixCounters.put(s, current);
			totalCounter += current;
		}
		System.out.println(totalCounter);
	}

	private static void doBulkLoad(LoadIncrementalHFiles bulkLoad, Path dir, HTable table) throws InterruptedException{
		try{
			long start = System.currentTimeMillis();
			bulkLoad.doBulkLoad(dir, table);
			long bulkTime = System.currentTimeMillis()-start;
			System.out.println(table.getTableDescriptor().getNameAsString()+" bulkLoad time: "+bulkTime);
		}
		catch(java.io.IOException e){
			System.out.println("Bulk load taking longer than usual: sleeping for 5 minutes before proceeding to the next table..");
			Thread.sleep(300000);
		}
	}
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		try {
			if (args.length != 3){
				System.out.println("Usage: bulkLoad <inputPath> <inputSizeEstimate in MB> <outputPath>");
				return;
			}
			
			Path input = new Path(args[0]);
			String outputPath = args[2];
			Path resourceIds = new Path(outputPath+TripleToResource.RESOURCE_IDS_DIR);
			Path convertedTripletsPath = new Path(outputPath+ResourceToTriple.TEMP_TRIPLETS_DIR);
			
			Path string2IdInput = new Path(outputPath+TripleToResource.ID2STRING_DIR);
			Path string2IdOutput = new Path(outputPath+StringIdAssoc.STRING2ID_DIR);
			
			HBaseConnection con = new HBaseConnection();
			HBPrefixMatchSchema.createCounterTable(con.getAdmin());
			
			/*start = System.currentTimeMillis();
			
			JobConf j1 = createTripleToResourceJob(input, resourceIds, Integer.parseInt(args[1]));
			j1.set("outputPath", outputPath);
			RunningJob runningJ1 = JobClient.runJob(j1);
			
			long firstJob = System.currentTimeMillis()-start;
			System.out.println("First pass finished in "+firstJob+" ms");
			
			//retrieve counter values and build a SortedMap
			retrieveCounters(runningJ1);
			long startPartition = HBPrefixMatchSchema.updateLastCounter(tripleToResourceReduceTasks, con.getConfiguration())+1;
			//long startPartition = HBPrefixMatchSchema.getLastCounter(con.getConfiguration())+1;
			
			
			//SECOND PASS-----------------------------------------------	
			
			JobConf j2 = createResourceToTripleJob(resourceIds, convertedTripletsPath);	
			JobClient.runJob(j2);
			
			System.out.println("Second pass finished");
			
			//read counters from file
			buildCountersFromFile();
			*/
			//create all tables containing PrefixMatch schema
			//tempCounters();
			System.out.println(totalStringCount+" : "+numericalCount);
			HBaseClientSolution sol = HBaseFactory.getHBaseSolution(HBPrefixMatchSchema.SCHEMA_NAME, con, null);		
			
			HBPrefixMatchSchema prefMatchSchema = (HBPrefixMatchSchema)sol.schema;
			prefMatchSchema.setObjectPrefixTableSplitInfo(totalStringCount, numericalCount);
			prefMatchSchema.setId2StringTableSplitInfo(tripleToResourceReduceTasks, 0);//TODO change to start partition
			prefMatchSchema.setString2IdTableSplitInfo(totalStringCount, sufixCounters);
			//sol.schema.create();
			
			LoadIncrementalHFiles bulkLoad = new LoadIncrementalHFiles(con.getConfiguration());
			/*
			//String2Id PASS----------------------------------------------
					
			Job j3 = createString2IdJob(con, string2IdInput, string2IdOutput);
			j3.waitForCompletion(true);
			
			//string2Id = new HTable(con.getConfiguration(), HBPrefixMatchSchema.STRING2ID);
			doBulkLoad(bulkLoad, string2IdOutput, string2Id);
			
			System.out.println("Finished bulk load for String2Id table");//=====================//=============
			
			/*Path id2StringOutput = new Path(outputPath+StringIdAssoc.ID2STRING_DIR);
			Job j4 = createId2StringJob(con, string2IdInput, id2StringOutput);
			j4.waitForCompletion(true);
			
			doBulkLoad(bulkLoad, id2StringOutput, id2String);
			
			System.out.println("Finished bulk load for Id2String table");//=====================//==================================
			*/
			Path spocPath = new Path(outputPath+"/"+HBPrefixMatchSchema.TABLE_NAMES[HBPrefixMatchSchema.SPOC]);
			Path pocsPath = new Path(outputPath+"/"+HBPrefixMatchSchema.TABLE_NAMES[HBPrefixMatchSchema.POCS]);
			Path cspoPath = new Path(outputPath+"/"+HBPrefixMatchSchema.TABLE_NAMES[HBPrefixMatchSchema.CSPO]);
			Path cpsoPath = new Path(outputPath+"/"+HBPrefixMatchSchema.TABLE_NAMES[HBPrefixMatchSchema.CPSO]);
			Path ocspPath = new Path(outputPath+"/"+HBPrefixMatchSchema.TABLE_NAMES[HBPrefixMatchSchema.OCSP]);
			Path ospcPath = new Path(outputPath+"/"+HBPrefixMatchSchema.TABLE_NAMES[HBPrefixMatchSchema.OSPC]);
			/*
			//SPOC--------------------
			Job j5 = createPrefixMatchJob(con, convertedTripletsPath, spocPath, HBPrefixMatchSchema.SPOC, PrefixMatch.PrefixMatchSPOCMapper.class);
			j5.waitForCompletion(true);
			
			doBulkLoad(bulkLoad, spocPath, currentTable);
	
			//CSPO----------------------
			Job j7 = createPrefixMatchJob(con, convertedTripletsPath, cspoPath, HBPrefixMatchSchema.CSPO, PrefixMatch.PrefixMatchCSPOMapper.class);
			j7.waitForCompletion(true);
			
			doBulkLoad(bulkLoad, cspoPath, currentTable);
			
			//CPSO-------------------------
			Job j8 = createPrefixMatchJob(con, convertedTripletsPath, cpsoPath, HBPrefixMatchSchema.CPSO, PrefixMatch.PrefixMatchCPSOMapper.class);
			j8.waitForCompletion(true);
			
			doBulkLoad(bulkLoad, cpsoPath, currentTable);
			
			//OCSP--------------------------			
			Job j9 = createPrefixMatchJob(con, convertedTripletsPath, ocspPath, HBPrefixMatchSchema.OCSP, PrefixMatch.PrefixMatchOCSPMapper.class);
			j9.waitForCompletion(true);
			
			doBulkLoad(bulkLoad, ocspPath, currentTable);
			
			//OSPC---------------------------
			Job j10 = createPrefixMatchJob(con, convertedTripletsPath, ospcPath, HBPrefixMatchSchema.OSPC, PrefixMatch.PrefixMatchOSPCMapper.class);
			j10.waitForCompletion(true);
			
			doBulkLoad(bulkLoad, ospcPath, currentTable);
			
			//POCS---------------------
			Job j6 = createPrefixMatchJob(con, convertedTripletsPath, pocsPath, HBPrefixMatchSchema.POCS, PrefixMatch.PrefixMatchPOCSMapper.class);
			j6.waitForCompletion(true);
			*/
			currentTable = new HTable(con.getConfiguration(), HBPrefixMatchSchema.TABLE_NAMES[HBPrefixMatchSchema.POCS]);
			doBulkLoad(bulkLoad, pocsPath, currentTable);
			
			con.close();
			
		} catch (IOException e) {
			e.printStackTrace();
		}
		catch(Exception e){
			e.printStackTrace();
		}
	}

}
