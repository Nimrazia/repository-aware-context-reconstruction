package org.thesis.eval;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class CsvUtil {
  static List<List<String>> read(Path p) throws IOException {
    List<List<String>> out=new ArrayList<>();
    try(BufferedReader br=Files.newBufferedReader(p,StandardCharsets.UTF_8)){
      StringBuilder rec=new StringBuilder(); boolean quoted=false; int ch;
      while((ch=br.read())!=-1){char c=(char)ch; if(c=='"') quoted=!quoted; if((c=='\n'||c=='\r')&&!quoted){if(rec.length()>0){out.add(parse(rec.toString()));rec.setLength(0);} if(c=='\r')br.mark(1); continue;} rec.append(c);} if(rec.length()>0)out.add(parse(rec.toString()));
    } return out;
  }
  static List<String> parse(String s){List<String> r=new ArrayList<>();StringBuilder b=new StringBuilder();boolean q=false;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='"'){if(q&&i+1<s.length()&&s.charAt(i+1)=='"'){b.append('"');i++;}else q=!q;}else if(c==','&&!q){r.add(b.toString());b.setLength(0);}else b.append(c);}r.add(b.toString());return r;}
  static String q(Object o){String s=o==null?"":String.valueOf(o); if(s.contains(",")||s.contains("\n")||s.contains("\r")||s.contains("\""))return '"'+s.replace("\"","\"\"")+'"'; return s;}
  static void write(Path p,List<String> head,List<List<?>> rows) throws IOException {Files.createDirectories(p.getParent());try(BufferedWriter w=Files.newBufferedWriter(p,StandardCharsets.UTF_8)){w.write(String.join(",",head.stream().map(CsvUtil::q).toList()));w.newLine();for(List<?> row:rows){w.write(String.join(",",row.stream().map(CsvUtil::q).toList()));w.newLine();}}}
}
