package org.thesis.eval;
import java.nio.file.*;import java.util.*;
final class ManifestLoader {
 static List<Models.ManifestRow> load(Path p)throws Exception{List<List<String>> all=CsvUtil.read(p);if(all.isEmpty())return List.of();Map<String,Integer> h=new HashMap<>();for(int i=0;i<all.get(0).size();i++)h.put(all.get(0).get(i).replace("\uFEFF",""),i);List<Models.ManifestRow> out=new ArrayList<>();for(int i=1;i<all.size();i++){List<String> r=all.get(i);out.add(new Models.ManifestRow(I(r,h,"repository_index"),S(r,h,"repository_slug"),I(r,h,"method_number_in_repository"),I(r,h,"selected_method_id"),I(r,h,"global_method_number"),S(r,h,"function_name"),S(r,h,"source_file"),S(r,h,"commit_hash"),I(r,h,"line_start"),I(r,h,"line_end"),D(r,h,"fan_out"),S(r,h,"github_link")));}return out;}
 static String S(List<String>r,Map<String,Integer>h,String k){Integer i=h.get(k);return i==null||i>=r.size()?"":r.get(i);}static int I(List<String>r,Map<String,Integer>h,String k){try{return Integer.parseInt(S(r,h,k));}catch(Exception e){return 0;}}static double D(List<String>r,Map<String,Integer>h,String k){try{return Double.parseDouble(S(r,h,k));}catch(Exception e){return Double.NaN;}}
}
