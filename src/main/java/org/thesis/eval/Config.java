package org.thesis.eval;

import java.io.*;
import java.nio.file.*;
import java.util.*;

final class Config {
  final Properties p=new Properties();
  final Path base;
  Config(Path file) throws IOException { base=file.toAbsolutePath().getParent().getParent(); try(InputStream in=Files.newInputStream(file)){p.load(in);} }
  Path path(String key){return base.resolve(p.getProperty(key)).normalize();}
  boolean bool(String k,boolean d){return Boolean.parseBoolean(p.getProperty(k,String.valueOf(d)));}
  int integer(String k,int d){try{return Integer.parseInt(p.getProperty(k,String.valueOf(d)));}catch(Exception e){return d;}}
  double decimal(String k,double d){try{return Double.parseDouble(p.getProperty(k,String.valueOf(d)));}catch(Exception e){return d;}}
}
