package com.trough.hot;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.res.*;
import android.content.res.loader.*;
import android.os.ParcelFileDescriptor;
import java.io.*;
/** Imported package resources and paths; does not initialize any game code. */
final class GameAssetContext extends ContextWrapper {
 final File directory;final Resources resources;final ApplicationInfo info;final ClassLoader loader;
 GameAssetContext(Context host,File directory,ClassLoader loader)throws Exception{
  super(host);this.directory=directory;this.loader=loader;
  ResourcesLoader assets=new ResourcesLoader();
  for(String name:new String[]{"base.apk","split_InstallTime.apk","split_config.arm64_v8a.apk"})try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(new File(directory,name),ParcelFileDescriptor.MODE_READ_ONLY)){assets.addProvider(ResourcesProvider.loadFromApk(fd));}
  resources=new Resources(Resources.getSystem().getAssets(),host.getResources().getDisplayMetrics(),host.getResources().getConfiguration());resources.addLoaders(assets);
  info=new ApplicationInfo(host.createPackageContext("com.kitkagames.fallbuddies",Context.CONTEXT_IGNORE_SECURITY).getApplicationInfo());
  info.sourceDir=new File(directory,"base.apk").getPath();info.publicSourceDir=info.sourceDir;
  info.splitSourceDirs=new String[]{new File(directory,"split_InstallTime.apk").getPath(),new File(directory,"split_config.arm64_v8a.apk").getPath()};info.splitPublicSourceDirs=info.splitSourceDirs.clone();
  info.nativeLibraryDir=new File(directory,"lib").getPath();info.dataDir=new File(directory,"data").getPath();info.uid=host.getApplicationInfo().uid;
 }
 public Resources getResources(){return resources;}public AssetManager getAssets(){return resources.getAssets();}
 public ClassLoader getClassLoader(){return loader;}public ApplicationInfo getApplicationInfo(){return info;}
 public String getPackageName(){return "com.kitkagames.fallbuddies";}public Context getApplicationContext(){return this;}
 public String getPackageCodePath(){return info.sourceDir;}public String getPackageResourcePath(){return info.publicSourceDir;}
 File local(String name){File file=new File(directory,"data/"+name);file.mkdirs();return file;}
 public File getDataDir(){return local("");}public File getFilesDir(){return local("files");}public File getCacheDir(){return local("cache");}public File getCodeCacheDir(){return local("code_cache");}public File getNoBackupFilesDir(){return local("no_backup");}public File getObbDir(){return local("obb");}public File[] getObbDirs(){return new File[]{getObbDir()};}
 public SharedPreferences getSharedPreferences(String name,int mode){return getBaseContext().getSharedPreferences("stumble."+name,mode);}
}
