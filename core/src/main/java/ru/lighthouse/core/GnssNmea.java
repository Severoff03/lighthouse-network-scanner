/*
 * DOP and MSL altitude parsing adapted from GPSTest NmeaUtils.java.
 * Copyright (C) 2013-2019 Sean J. Barbeau
 * Licensed under the Apache License, Version 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0
 * Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * Lighthouse changes: checksum validation, all talkers, finite values, fix dimension.
 * Full upstream source and license: third_party/gpstest.
 */
package ru.lighthouse.core;

public final class GnssNmea {
    private GnssNmea() {}
    public static String[] fields(String sentence) {
        if(sentence==null)return null;
        String s=sentence.trim();int star=s.indexOf('*');
        if(!s.startsWith("$")||star<6||star!=s.length()-3)return null;
        int checksum=0;for(int i=1;i<star;i++)checksum^=s.charAt(i);
        try{if(checksum!=Integer.parseInt(s.substring(star+1),16))return null;}catch(NumberFormatException e){return null;}
        return s.substring(1,star).split(",",-1);
    }
    public static double number(String value) {
        try{double n=Double.parseDouble(value);return Double.isFinite(n)?n:Double.NaN;}catch(Exception e){return Double.NaN;}
    }
    public static int fixDimension(String sentence) {
        String[] t=fields(sentence);
        if(t==null||t.length<18||!t[0].matches("[A-Z]{2}GSA"))return 0;
        return t[2].equals("1")?1:t[2].equals("2")?2:t[2].equals("3")?3:0;
    }
    public static double[] dop(String sentence) {
        String[] t=fields(sentence);if(t==null||t.length<18||!t[0].matches("[A-Z]{2}GSA"))return null;
        // GPSTest indices: PDOP=15, HDOP=16, VDOP=17.
        double p=number(t[15]),h=number(t[16]),v=number(t[17]);
        return p>0&&h>0&&v>0?new double[]{p,h,v}:null;
    }
    public static double altitudeMeanSeaLevel(String sentence) {
        String[] t=fields(sentence);if(t==null||t.length<11)return Double.NaN;
        if(t[0].matches("[A-Z]{2}GGA")&&number(t[6])>0&&t[10].equals("M"))return number(t[9]);
        if(t[0].matches("[A-Z]{2}GNS")&&t[6].matches("[ADEFMPRSN]+")&&!t[6].matches("N+"))return number(t[9]);
        return Double.NaN;
    }
}
