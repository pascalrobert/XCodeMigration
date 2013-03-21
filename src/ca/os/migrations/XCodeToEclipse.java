package ca.os.migrations;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.MalformedURLException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.plist.PropertyListConfiguration;
import org.apache.commons.io.FileUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSData;
import com.webobjects.foundation.NSLog;
import com.webobjects.foundation.NSMutableArray;
import com.webobjects.foundation.NSMutableDictionary;
import com.webobjects.foundation.NSPropertyListSerialization;

/**
 * This tool copy WebObjects projects done with XCode (2.2 or later) to the Eclipse/WOLips environment.
 * Converted projects will be stored in a temporary folder where you can tell Eclipse to import projects.
 * The original Xcode project IS NOT modified in any way.
 * 
 * @author Pascal Robert <probert@os.ca>
 * @version	$Revision: 1.2 $, $Date: 2007/12/18 $
 * <br>&copy; 2007 OS Communications Informatiques, inc. Tous droits réservés.
 *
 */

public class XCodeToEclipse {

	/** Store the list of projects to import  */
	public NSMutableArray 	projects;		
	/** Path to the temporary workspace, loaded from the XML configuration file */
	public String 			pathToWorkspace;
	/** Path to an application project template done with WOLips */
	public String			applicationTemplatePath;
	/** Path to an framework project template done with WOLips */
	public String			frameworkTemplatePath;
	/** Path to the pbprojectdump binary, needed to convert a XCode project file as a easy to read plist */
	public String 			pathForPBDump = "/usr/local/bin/pbprojectdump";	
    /** Should .java files encoded in MacRoman converts to UTF-8 ? */
	public boolean			shouldConvertToUTF8 = true;
	
	public static void main(String[] args) {		
		XCodeToEclipse migration = new XCodeToEclipse();
		try {
			System.out.println("Starting the importation process...");
			migration.loadConfig(args);
			migration.cleanupTemplates();
			migration.parseProjects();
			System.out.println("All done!  Next step: you have to manually import (File -> Import -> Existing projects into Workspace) the projects into Eclipse.");
		} catch (Exception e1) {
			e1.printStackTrace();
			System.exit(1);
		}
		
	}
	
	public XCodeToEclipse() {
		projects = new NSMutableArray();
	}
	
	/**
	 * This method load the configuration from an XML file.  This file's path is specified as a command-line argument
	 * 
	 * @param an array of arguments coming from the command-line options
	 * @throws an exception if the XML parsing didn't work
	 */
	public void loadConfig(String[] args) throws Exception {
		if (args.length == 1) {
			try {

				DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
				DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
				Document xmlDoc = docBuilder.parse(new File(args[0]));

				for (int iterator = 0; iterator < xmlDoc.getElementsByTagName("project").getLength(); iterator++) {
					ProjectInfo projectDetail = new ProjectInfo();
					for (int i = 0; i < xmlDoc.getElementsByTagName("project").item(iterator).getChildNodes().getLength(); i++) {
						if (xmlDoc.getElementsByTagName("project").item(iterator).getChildNodes().item(i).hasChildNodes()) {
							Node node = xmlDoc.getElementsByTagName("project").item(iterator).getChildNodes().item(i);
							if (node.getNodeName().equals("path")) {
								projectDetail.setPath(node.getChildNodes().item(0).getNodeValue());
							}
							if (node.getNodeName().equals("name")) {
								projectDetail.setName(node.getChildNodes().item(0).getNodeValue());
							}                                                            
						}
					}
					projects.addObject(projectDetail);
				}

				for (int iterator = 0; iterator < xmlDoc.getElementsByTagName("workspacePath").getLength(); iterator++) {
					for (int i = 0; i < xmlDoc.getElementsByTagName("workspacePath").item(iterator).getChildNodes().getLength(); i++) {
						Node node = xmlDoc.getElementsByTagName("workspacePath").item(iterator).getChildNodes().item(i);
						pathToWorkspace = node.getNodeValue();
					}
				}                

				for (int iterator = 0; iterator < xmlDoc.getElementsByTagName("applicationTemplatePath").getLength(); iterator++) {
					for (int i = 0; i < xmlDoc.getElementsByTagName("applicationTemplatePath").item(iterator).getChildNodes().getLength(); i++) {
						Node node = xmlDoc.getElementsByTagName("applicationTemplatePath").item(iterator).getChildNodes().item(i);
						applicationTemplatePath = node.getNodeValue();
					}
				}     
				
				for (int iterator = 0; iterator < xmlDoc.getElementsByTagName("frameworkTemplatePath").getLength(); iterator++) {
					for (int i = 0; i < xmlDoc.getElementsByTagName("frameworkTemplatePath").item(iterator).getChildNodes().getLength(); i++) {
						Node node = xmlDoc.getElementsByTagName("frameworkTemplatePath").item(iterator).getChildNodes().item(i);
						frameworkTemplatePath = node.getNodeValue();
					}
				}     
				
				for (int iterator = 0; iterator < xmlDoc.getElementsByTagName("convertToUTF8").getLength(); iterator++) {
					for (int i = 0; i < xmlDoc.getElementsByTagName("convertToUTF8").item(iterator).getChildNodes().getLength(); i++) {
						Node node = xmlDoc.getElementsByTagName("convertToUTF8").item(iterator).getChildNodes().item(i);
						shouldConvertToUTF8 = new Boolean(node.getNodeValue()).booleanValue();
					}
				}  
				
			} catch (Exception ex) {
				ex.printStackTrace();
				throw ex;
			}
		} else {
			throw new Exception ("Unable to locate or load the configuration file!\nUsage: java Importer pathToConfig.xml");
		}     
		
		File pbdump = new File(pathForPBDump);
		if (!(pbdump.exists())) {
			throw new Exception ("Unable to the \"pbprojectdump\" binary at " + pbdump.getPath());				
		}
	}
	
	/**
	 * This method will remove the default files that are created in the templates
	 * so that they are not part of the new project.
	 *
	 */
	public void cleanupTemplates() {
		try {
			File fileToCopy = new File(applicationTemplatePath);
			FileUtils.cleanDirectory(new File(fileToCopy.getAbsolutePath() + "/Components"));
			FileUtils.cleanDirectory(new File(fileToCopy.getAbsolutePath() + "/Sources"));
			FileUtils.cleanDirectory(new File(fileToCopy.getAbsolutePath() + "/Resources"));		
			FileUtils.cleanDirectory(new File(fileToCopy.getAbsolutePath() + "/build"));
			FileUtils.deleteDirectory(new File(fileToCopy.getAbsolutePath() + "/" + fileToCopy.getName() + ".xcodeproj"));
		
			fileToCopy = new File(frameworkTemplatePath);
			FileUtils.cleanDirectory(new File(fileToCopy.getAbsolutePath() + "/Resources"));
			FileUtils.cleanDirectory(new File(fileToCopy.getAbsolutePath() + "/build"));
			FileUtils.deleteDirectory(new File(fileToCopy.getAbsolutePath() + "/" + fileToCopy.getName() + ".xcodeproj"));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * This method try to find out if the XCode project is for building a WebObjects framework
	 * 
	 * @param The list of targets found in the Xcode project
	 * @return true if it's a framework project
	 */
	public boolean isFramework(NSMutableArray targets) {
		for (Iterator targetIterator = targets.iterator(); targetIterator.hasNext();) {
			NSMutableDictionary target = (NSMutableDictionary)targetIterator.next();
			if ((target.get("name") != null) && (((String)target.get("name")).equals("Application Server"))) {
				if ((target.get("isa") != null) && (((String)target.get("isa")).equals("PBXFrameworkTarget"))) {
					return true;
				}
			}
		}
		return false;
	}
	
	public File projectExist(ProjectInfo project) {
		File pathToXCodeProject = null;
		pathToXCodeProject = new File(project.getPath() + project.getName() + ".xcodeproj/");
		if (pathToXCodeProject.exists()) {
			return pathToXCodeProject;
		}

		pathToXCodeProject = new File(project.getPath() + project.getName() + ".xcode/");
		if (pathToXCodeProject.exists()) {
			return pathToXCodeProject;
		}
		
		pathToXCodeProject = new File(project.getPath() + project.getName() + ".pbproj/");
		if (pathToXCodeProject.exists()) {
			return pathToXCodeProject;
		}
		
    pathToXCodeProject = new File(project.getPath() + project.getName() + ".project");
    if (pathToXCodeProject.exists()) {
      return pathToXCodeProject;
    }
		
		return pathToXCodeProject;
	}
	
	public void parseSubProject(File pathToXCodeProject, String subProjectName) throws ConfigurationException, MalformedURLException {
	  File subProjectPath = new File(pathToXCodeProject.getParent() + "/" + subProjectName + "/PB.project");
    NSMutableDictionary plist = (NSMutableDictionary)NSPropertyListSerialization.propertyListWithPathURL(subProjectPath.toURL());
    NSMutableDictionary filesTables = (NSMutableDictionary)plist.get("FILESTABLE");
    NSMutableArray<String> javaClasses = (NSMutableArray<String>)filesTables.get("CLASSES");
    NSMutableArray<String> webResources = (NSMutableArray<String>)filesTables.get("WEBSERVER_RESOURCES");
    NSMutableArray<String> resources = (NSMutableArray<String>)filesTables.get("WOAPP_RESOURCES");
    NSMutableArray<String> components = (NSMutableArray<String>)filesTables.get("WO_COMPONENTS");

    copyFiles(new File(pathToXCodeProject.getParent()),filesInfoForFiles(components, subProjectName));
    copyFiles(new File(pathToXCodeProject.getParent()),filesInfoForFiles(resources, subProjectName));
    copyFiles(new File(pathToXCodeProject.getParent()),filesInfoForFiles(webResources, subProjectName));
    copyFiles(new File(pathToXCodeProject.getParent()),filesInfoForFiles(javaClasses, subProjectName));
	}

	public NSMutableArray<FileInfo> filesInfoForFiles(NSArray<String> files) {
	  return filesInfoForFiles(files, null);
	}

	public NSMutableArray<FileInfo> filesInfoForFiles(NSArray<String> files, String subProjectName) {
	  NSMutableArray<FileInfo> paths = new NSMutableArray<XCodeToEclipse.FileInfo>();
	  if (files != null) {
	    for (String file: files) {
	      FileInfo projectFile = new FileInfo();
	      if (subProjectName != null) {
	        projectFile.setPath(subProjectName + "/" + file);
	      } else {
	        projectFile.setPath(file);

	      }
	      projectFile.setEncoding("NSWindowsCP1252StringEncoding");
	      paths.addObject(projectFile);
	    }
	  } else {
	    NSLog.out.appendln(subProjectName);
	  }
	  return paths;
	}
	
	/**
	 * This method is the big one.  It parse the list of projects, found in the XML configuration, 
	 * create a new Eclipse project based on the templates, build the original files list from the 
	 * Xcode project and copy the files from the Xcode project to the Eclipse project.
	 * 
	 * This method call the pbprojectdump tool that Apple ships with the dev tools.  This tool is 
	 * located at /Developer/Tools/pbprojectdump, make sure it's there.  This tool is needed to 
	 * create a readable Plist of the Xcode project.
	 * @throws ConfigurationException 
	 *
	 */
	public void parseProjects() throws ConfigurationException {
		NSData plistData;
		File pathToXCodeProject = null;

		try {
			for (Iterator projectIterator = projects.iterator(); projectIterator.hasNext();) {		
				ProjectInfo project = (ProjectInfo)projectIterator.next();
				if (!(project.getPath().endsWith("/"))) {
					project.setPath(project.getPath() + "/");
				}
				
				pathToXCodeProject = projectExist(project);
				
				if (pathToXCodeProject != null) {
					System.out.println("Converting " + project.getName() + "...");

					NSMutableArray<FileInfo> paths = new NSMutableArray<FileInfo>();

					File[] projectBuilderFiles = pathToXCodeProject.getParentFile().listFiles(new PBProjectExt());
					
					if ((projectBuilderFiles != null) && (projectBuilderFiles.length == 1)) {
					  
	           NSMutableDictionary plist = (NSMutableDictionary)NSPropertyListSerialization.propertyListWithPathURL(projectBuilderFiles[0].toURL());
	           NSMutableDictionary filesTables = (NSMutableDictionary)plist.get("FILESTABLE");
	           NSMutableArray<String> frameworks = (NSMutableArray<String>)filesTables.get("FRAMEWORKS");
	           NSMutableArray<String> javaClasses = (NSMutableArray<String>)filesTables.get("CLASSES");
	           NSMutableArray<String> subProjects = (NSMutableArray<String>)filesTables.get("SUBPROJECTS");
	           NSMutableArray<String> webResources = (NSMutableArray<String>)filesTables.get("WEBSERVER_RESOURCES");
	           NSMutableArray<String> resources = (NSMutableArray<String>)filesTables.get("WOAPP_RESOURCES");
	           NSMutableArray<String> components = (NSMutableArray<String>)filesTables.get("WO_COMPONENTS");
	           String projectType = (String)plist.get("PROJECTTYPE");
	           boolean isFramework = true;
	           if ("JavaWebObjectsApplication".equals(projectType)) {
	             isFramework = false;
	           }
	           createEclipseProject(project,isFramework);

	           for (String subProject: subProjects) {
	             parseSubProject(pathToXCodeProject, subProject);
	           }
	           
             copyFiles(new File(project.getPath()),filesInfoForFiles(components));
             copyFiles(new File(project.getPath()),filesInfoForFiles(resources));
             copyFiles(new File(project.getPath()),filesInfoForFiles(webResources));
             copyFiles(new File(project.getPath()),filesInfoForFiles(javaClasses));
             copyFiles(new File(project.getPath()),filesInfoForFiles(frameworks));
	           	           
					} else {

					  Process externalProcess = Runtime.getRuntime().exec(pathForPBDump + " " + pathToXCodeProject.getAbsolutePath());
					  BufferedReader results = new BufferedReader(new InputStreamReader(externalProcess.getInputStream()));
					  String str;
					  StringBuilder strPlist = new StringBuilder();
					  while ((str = results.readLine()) != null) {
					    strPlist.append(str + "\n");
					  }

					  plistData = new NSData(strPlist.toString(),"UTF-8");
					  String plistString = new String(plistData.bytes(0, plistData.length()));
					  NSMutableDictionary plist = (NSMutableDictionary)NSPropertyListSerialization.propertyListFromString(plistString);
					  NSMutableDictionary rootObject = (NSMutableDictionary)plist.get("rootObject");

					  NSMutableArray targets = (NSMutableArray)rootObject.get("targets");

					  NSMutableDictionary mainGroup = (NSMutableDictionary)rootObject.get("mainGroup");
					  NSMutableArray mainGroupChildren = (NSMutableArray)mainGroup.get("children");

					  for (int i = 0; i < mainGroupChildren.count(); i++) {
					    NSMutableDictionary childOfChild = (NSMutableDictionary)mainGroupChildren.objectAtIndex(i);
					    if ((childOfChild.get("name") == null) || ((childOfChild.get("name") != null) && (!(((String)childOfChild.get("name")).equals("Products"))))) {
					      createEclipseProject(project,isFramework(targets));
					      fetchFilesFromProject(childOfChild,"",paths,pathToXCodeProject);
					      copyFiles(new File(project.getPath()),paths);
					    }
					  }
					  str = null;
					  strPlist = null;
					  
					}
				}
			}
		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}		
	}
	
	/**
	 * This method create a new Eclipse/WOLips project based on the templates.
	 * It also modifies the "build.properties" and ".project" files in the 
	 * project with the correct project name.
	 * 
	 * @param The project to create
	 * @param If it's a framework project
	 */
	public void createEclipseProject(ProjectInfo project, boolean isFramework) {
		File pathToXCodeProject = new File(project.getPath());
		File fileToCopy = new File(applicationTemplatePath);
		if (isFramework) {
			fileToCopy = new File(frameworkTemplatePath);
		}
		File fileDestination = new File(pathToWorkspace + pathToXCodeProject.getName());
		
		try {
			FileUtils.copyDirectory(fileToCopy, fileDestination, true);
		} catch (IOException e) {
			e.printStackTrace();
		}		
		
		Properties buildProps = this.getBuildProperties(fileDestination);
		if (buildProps.getProperty("framework.name") != null) {
			buildProps.setProperty("framework.name",project.getName());
		}
		if (buildProps.getProperty("framework.name.lowercase") != null) {
			buildProps.setProperty("framework.name.lowercase",project.getName().toLowerCase());
		}			
		if (buildProps.getProperty("project.name") != null) {
			buildProps.setProperty("project.name",project.getName());
		}
		if (buildProps.getProperty("project.name.lowercase") != null) {
			buildProps.setProperty("project.name.lowercase",project.getName().toLowerCase());
		}		
    if (buildProps.getProperty("project.type") != null) {
      if (isFramework) {
        buildProps.setProperty("project.type",project.getName().toLowerCase());        
      } else {
        buildProps.setProperty("project.type","framework");
      }
    } 
		
		this.saveBuildProperties(buildProps, fileDestination);

		DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder docBuilder;
		try {
			docBuilder = docFactory.newDocumentBuilder();
			Document xmlDoc = docBuilder.parse(new File(fileDestination + "/.project"));
			for (int iterator = 0; iterator < xmlDoc.getElementsByTagName("projectDescription").getLength(); iterator++) {
				for (int i = 0; i < xmlDoc.getElementsByTagName("projectDescription").item(iterator).getChildNodes().getLength(); i++) {
					if (xmlDoc.getElementsByTagName("projectDescription").item(iterator).getChildNodes().item(i).hasChildNodes()) {
						Node noeud = xmlDoc.getElementsByTagName("projectDescription").item(iterator).getChildNodes().item(i);
						if (noeud.getNodeName().equals("name")) {
							noeud.getChildNodes().item(0).setNodeValue(project.getName());
						}                                                            
					}
				}
			}
			Transformer transformer;
			try {
				transformer = TransformerFactory.newInstance().newTransformer();
				transformer.setOutputProperty(OutputKeys.INDENT, "yes");
				StreamResult result = new StreamResult(new FileWriter(fileDestination + "/.project"));
				DOMSource source = new DOMSource(xmlDoc);
				transformer.transform(source, result);
			} catch (TransformerConfigurationException e) {
				e.printStackTrace();
			} catch (TransformerFactoryConfigurationError e) {
				e.printStackTrace();
			} catch (TransformerException e) {
				e.printStackTrace();
			}
			
			docBuilder = docFactory.newDocumentBuilder();
			xmlDoc = docBuilder.parse(new File(fileDestination + "/build.xml"));
			for (int iterator = 0; iterator < xmlDoc.getElementsByTagName("project").getLength(); iterator++) {
				for (int i = 0; i < xmlDoc.getElementsByTagName("project").item(iterator).getChildNodes().getLength(); i++) {
					xmlDoc.getElementsByTagName("project").item(iterator).getAttributes().getNamedItem("name").setNodeValue(project.getName());
				}
			}
			
			try {
				transformer = TransformerFactory.newInstance().newTransformer();
				transformer.setOutputProperty(OutputKeys.INDENT, "yes");
				StreamResult result = new StreamResult(new FileWriter(fileDestination + "/build.xml"));
				DOMSource source = new DOMSource(xmlDoc);
				transformer.transform(source, result);
			} catch (TransformerConfigurationException e) {
				e.printStackTrace();
			} catch (TransformerFactoryConfigurationError e) {
				e.printStackTrace();
			} catch (TransformerException e) {
				e.printStackTrace();
			}

		} catch (ParserConfigurationException e) {
			e.printStackTrace();
		} catch (SAXException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * This method take the list of files, obtained with fetchFilesFromProject, 
	 * and copy them to the new Eclipse/WOLips project.
	 * 
	 * It also convert any file with a MacRoman enconding to UTF-8
	 * 
	 * @param a java.io.File object that link to the original Xcode project
	 * @param an array that store the list of file paths to import
	 * @throws ConfigurationException 
	 * @throws ConfigurationException 
	 */
	
	public void copyFiles(File pathToXCodeProject, NSMutableArray<FileInfo> paths) throws ConfigurationException  {
		for (Iterator<FileInfo> iter = paths.iterator(); iter.hasNext();) {
			FileInfo fileInfo = (FileInfo)iter.next();
			String path = fileInfo.getPath();
			String eclipseFolder = "WebServerResources";
			File fileToCopy = new File(pathToXCodeProject.getAbsolutePath() + "/" + path);
			
			/* I know, I can shorten those ifs, but it's clearer like this */
			if (path.endsWith(".java")) {
				eclipseFolder = "Sources";
			}
			if ((path.endsWith(".wo")) || (path.endsWith(".api"))) {
				eclipseFolder = "Components";
				if (fileToCopy.getParent().endsWith(".lproj")) {
					path = fileToCopy.getParentFile().getName() + "/" + fileToCopy.getName();
				} else {
					path = fileToCopy.getName();
				}
			}
			if (path.endsWith(".strings")) {
				eclipseFolder = "Resources";
				if (fileToCopy.getParent().endsWith(".lproj")) {
					path = fileToCopy.getParentFile().getName() + "/" + fileToCopy.getName();
				} else {
					path = fileToCopy.getName();
				}
			}
			if (path.endsWith("Properties")) {
				eclipseFolder = "Resources";
				path = fileToCopy.getName();
			}	
			if (path.endsWith(".eomodeld")) {
				eclipseFolder = "Resources";
				path = fileToCopy.getName();
			}		
			if (path.endsWith(".prop")) {
				eclipseFolder = "Resources";
				path = fileToCopy.getName();
			}		
			if (path.endsWith(".properties")) {
				eclipseFolder = "Resources";
				path = fileToCopy.getName();
			}	
			if (path.endsWith(".rpt")) {
				eclipseFolder = "Resources";
				path = fileToCopy.getName();
			}	
			if (path.endsWith(".eotemplate")) {
				eclipseFolder = "Resources";
				path = fileToCopy.getName();
			}	
			if (path.endsWith(".jar")) {
				eclipseFolder = "Libraries";
				path = fileToCopy.getName();
			}
			if (path.endsWith(".d2wmodel")) {
				eclipseFolder = "Resources";
				path = fileToCopy.getName();
			}					
			if (path.endsWith("javaCheckStyle.xml")) {
				eclipseFolder = "Resources";
				path = fileToCopy.getName();
			}							
			if (path.endsWith(".plist")) {
				eclipseFolder = "Resources";
				path = fileToCopy.getName();
			}	
			if (path.endsWith(".wsdd")) {
				eclipseFolder = "Resources";
				path = fileToCopy.getName();
			}
			
			eclipseFolder = "/" + eclipseFolder + "/";
			
			if (path.startsWith("../")) {
				System.out.println("path starting with ../: " + path);
			}
			
			/* Some paths that are relative to the Xcode project has "../" in them 
			 * and we have to remove them or else commons-io will not be able to create the path */
			if ((path.contains("../")) && (!(path.startsWith("../")))) {
				int indexOfPath = path.indexOf("../");
				File parent = new File(pathToXCodeProject + "/" + path.substring(0, indexOfPath - 1));
				String correctPath = parent.getParent() + path.substring(indexOfPath + 2, path.length());
				path = correctPath.substring(pathToXCodeProject.getAbsolutePath().length(), correctPath.length());
			}			
			/* We have many projects where the .java files are stored in a "Classes" folder, 
			 * and we need to remove that name from the path or else Eclipse will not compile the files */
			if (path.startsWith("Classes")) {
				path = path.substring(7);
			}
			if (path.startsWith("/Classes")) {
				path = path.substring(8);
			}			

			File fileDestination = new File(pathToWorkspace + pathToXCodeProject.getName() + eclipseFolder + path);
			if (fileInfo.getNewPath() != null) {
				fileDestination = new File(pathToWorkspace + pathToXCodeProject.getName() + eclipseFolder + fileInfo.getNewPath());
			} 
			
			if (fileToCopy.getName().equals("Application.java")) {
				File propsFile = new File(pathToWorkspace + pathToXCodeProject.getName());
				Properties buildProps = this.getBuildProperties(propsFile);
				if (fileInfo.getPackageName() != null) {
					buildProps.setProperty("principalClass",fileInfo.getPackageName() + ".Application");
				} else {
					buildProps.setProperty("principalClass","Application");
				}
				this.saveBuildProperties(buildProps, propsFile);				
			}
			
			if (fileToCopy.exists()) {
				if (fileToCopy.isDirectory()) {
					try {
						FileUtils.copyDirectory(fileToCopy, fileDestination, true);

						if ((fileDestination.getName().endsWith(".wo")) && (shouldConvertToUTF8)) {
							String[] extensions = new String[1];

							extensions[0] = "woo";
							LinkedList woofileList = (LinkedList)FileUtils.listFiles(fileDestination, extensions, false);
							extensions[0] = "html";
							LinkedList htmlfileList = (LinkedList)FileUtils.listFiles(fileDestination, extensions, false);
							extensions[0] = "wod";
							LinkedList wodfileList = (LinkedList)FileUtils.listFiles(fileDestination, extensions, false);

							for (Iterator<File> fileIterator = woofileList.iterator(); fileIterator.hasNext();) {
								File wooFile = (File)fileIterator.next();
								PropertyListConfiguration wooPlist = new PropertyListConfiguration(wooFile);
								if ((wooPlist.getString("encoding") == null) || (wooPlist.getString("encoding").equals("NSMacOSRomanStringEncoding"))) {
									System.out.println("Please change (in the WOO file) the encoding property for this file:" + wooFile.getName());
												
									convertFileToUnicode(wooFile,wooFile);

									for (Iterator<File> htmlFileIterator = htmlfileList.iterator(); htmlFileIterator.hasNext();) {
										File htmlFile = (File)htmlFileIterator.next();
										convertFileToUnicode(htmlFile,htmlFile);
									}

									for (Iterator<File> wodFileIterator = wodfileList.iterator(); wodFileIterator.hasNext();) {
										File wodFile = (File)wodFileIterator.next();
										convertFileToUnicode(wodFile,wodFile);
									}
								} 
							}
						}
					} catch (IOException e) {
						e.printStackTrace();
					}					
				} else {
					try {
						/* Dead to MacRoman ! */
						if ((fileInfo.isMacRoman()) && (shouldConvertToUTF8)) {
							if (!(fileDestination.getParentFile().exists())) {
								fileDestination.getParentFile().mkdirs();
							}
							convertFileToUnicode(fileToCopy,fileDestination);
						} else {
							FileUtils.copyFile(fileToCopy, fileDestination, true);							
						}
						
					} catch (IOException e) {
						e.printStackTrace();
					}					
				}
			} else {
				if (fileToCopy.getAbsolutePath().endsWith(".framework")) {
					if (!(fileToCopy.getAbsolutePath().contains("/System/Library/Frameworks/"))) {
						System.out.println("please include this framework in your application : " + fileToCopy.getName());
					}
				} else {
					System.out.println("don't exist : " + fileToCopy.getAbsolutePath());					
				}
			}
		}
	}
	
	/**
	 * This method parse the Plist from the original Xcode project and make a list of files 
	 * to copy to the new project.
	 * 
	 * @param parent
	 * @param full path to the file to copy
	 * @param list of paths to copy
	 * @param path to the original Xcode project
	 */
	public void fetchFilesFromProject(NSMutableDictionary parent, String path, NSMutableArray<FileInfo> paths, File pathToXCodeProject) {	
		if (("PBXGroup".equals(parent.get("isa"))) || ("PBXVariantGroup".equals(parent.get("isa")))) {
			if (parent.get("path") != null) {
				path = path + "/" + (String)parent.get("path");
			}
			
			if ((parent.get("sourceTree") != null) && ("SOURCE_ROOT".equals(parent.get("sourceTree")))) {
				if (parent.get("path") != null) {
					path = (String)parent.get("path");					
				} else {
					// Files that are relative to project instead of related to a group
					path = "/";
				}
			}
			if (parent.get("children") != null) {
				for (Iterator iter = ((NSMutableArray)parent.get("children")).iterator(); iter.hasNext();) {
					fetchFilesFromProject((NSMutableDictionary)iter.next(),path,paths,pathToXCodeProject);
				}
			} 
		}
		
		// Saw this in Project Builder project
		if ("PBXFolderReference".equals(parent.get("isa"))) {
			FileInfo projectFile = new FileInfo();
			projectFile.setPath((String)parent.get("path"));
			projectFile.setNewPath((String)parent.get("path"));
			projectFile.setEncoding((String)parent.get("fileEncoding"));
			paths.addObject(projectFile);
		}
		
		if (parent.get("isa").equals("PBXFileReference")) {
			FileInfo projectFile = new FileInfo();
			projectFile.setNewPath(null);
			
			if (path.matches("^.*\\.lproj/.*$")) {
				path = (String)parent.get("path");
			} else if ((parent.get("sourceTree") != null) && ("SOURCE_ROOT".equals(parent.get("sourceTree")))) {
				path = (String)parent.get("path");
			} else {
				path = path + "/" + (String)parent.get("path");
			}
						
			if (path.matches("^.*\\.subproj/.*$")) {
				String tempPath = "";
				String[] pathElements = path.split("/");
				for (int indexIterator = 0; indexIterator < pathElements.length; indexIterator++) {
					if ((pathElements[indexIterator].length() > 0) && (!(pathElements[indexIterator].contains(".subproj")))) {
						tempPath = tempPath + "/" + pathElements[indexIterator];
					}
				}
				projectFile.setNewPath(tempPath);
			}
			
			if (!paths.contains(path)) {	
				if (path.endsWith(".java")) {
					try {
						BufferedReader javaClass = new BufferedReader(new FileReader(new File(pathToXCodeProject.getParentFile().getAbsolutePath() + "/" + path)));
					    String strBuffer;
						while ((strBuffer = javaClass.readLine()) != null) { 
							Pattern regexPattern = Pattern.compile("^package\\s+(.*);$");
							Matcher regexMatcher = regexPattern.matcher(strBuffer);
							if (regexMatcher.matches()) {
								String packageName = regexMatcher.group(1);
								projectFile.setPackageName(packageName);
								packageName = packageName.replace('.', '/');
								if (projectFile.getNewPath() != null) {
									projectFile.setNewPath(packageName + "/" + projectFile.getNewPath());									
								} else {
									projectFile.setNewPath("/" + packageName + "/" + (String)parent.get("path"));									
								}
							}
					    } 					
					} catch (FileNotFoundException e) {
						e.printStackTrace();
					} catch (IOException e) { 
						e.printStackTrace();
					}
				}
				projectFile.setPath(path);
				projectFile.setEncoding((String)parent.get("fileEncoding"));
				paths.addObject(projectFile);
			}
		}
	}
	
	public Properties getBuildProperties(File fileDestination) {
		Properties buildProps = new Properties();
		try {
			buildProps.load(new FileInputStream(new File(fileDestination + "/build.properties")));
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		return buildProps;
	}
	
	public void saveBuildProperties(Properties buildProps, File fileDestination) {
		try {
			buildProps.store(new FileOutputStream(new File(fileDestination + "/build.properties")),null);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	/*
	 * This method will convert a file from MacRoman to UTF-8.  If the original file and the final destination 
	 * have the same path and isSameFile evaluates to true, the destination will be named with .utf8 as the extension
	 * and after the conversion is done, the original file is deleted and the destination is renamed without the .utf8
	 */
	public void convertFileToUnicode(File fileToCopy, File fileDestination) throws IOException {
		boolean isSameFile = false;
		if (fileToCopy.getAbsolutePath().equals(fileDestination.getAbsolutePath())) {
			isSameFile = true;
		}
		FileInputStream originalFileStream = new FileInputStream(fileToCopy);
	    InputStreamReader readerIn = new InputStreamReader(originalFileStream,"MacRoman");
	    if (isSameFile) {
	    	fileDestination = new File(fileDestination.getAbsolutePath() + ".utf8");
	    }
	    FileOutputStream  convertedFileStream = new FileOutputStream(fileDestination);
	    Writer writerOut = new OutputStreamWriter(convertedFileStream,"UTF-8");
	    int charIndex; 
	    while ((charIndex = readerIn.read()) != -1) { 
	    	writerOut.write((char) charIndex);
	    } 
	    writerOut.close();
	    originalFileStream.close();
	    convertedFileStream.close();
	    if (isSameFile) {
	    	String originalFileName = fileToCopy.getAbsolutePath();
	    	fileToCopy.delete();
	    	if (!(fileDestination.renameTo(new File(originalFileName)))) {
	    		System.out.println("can't rename the file " + fileDestination.getAbsolutePath() + " to " + originalFileName);
	    	}
	    }
	}
	
	public class ProjectInfo {
		private String name;
		private String path;
		
		public ProjectInfo() {
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getPath() {
			return path;
		}

		public void setPath(String path) {
			this.path = path;
		}
	}
	
	public class FileInfo {
		private String path;
		private String name;
		private String encoding;
		private String newPath;
		private String packageName;
		
		public FileInfo() {
		}
		
		public String getEncoding() {
			return encoding;
		}
		
		/*
		 * 4 = UTF-8
		 * 30 = MacRoman
		 */		
		public void setEncoding(String encoding) {
			this.encoding = encoding;
		}
		
		public String getName() {
			return name;
		}
		
		public void setName(String name) {
			this.name = name;
		}
		
		public String getPath() {
			return path;
		}
		
		public void setPath(String path) {
			this.path = path;
		}
		
		public boolean isUnicode() {
			if ("4".equals(this.getEncoding())) {
				return true;
			}
			return false;
		}

		public boolean isMacRoman() {
			if ("30".equals(this.getEncoding())) {
				return true;
			}
			return false;
		}

		public String getNewPath() {
			return newPath;
		}

		public void setNewPath(String newPath) {
			this.newPath = newPath;
		}

		public String getPackageName() {
			return packageName;
		}

		public void setPackageName(String packageName) {
			this.packageName = packageName;
		}
		
	}
	
	public class PBProjectExt implements FilenameFilter { 
	  String ext; 
	  public PBProjectExt() { 
	  } 
	  public boolean accept(File dir, String name) { 
	    return name.equals("PB.project"); 
	  } 
	}
}
