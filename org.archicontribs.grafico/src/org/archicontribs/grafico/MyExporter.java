/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.grafico;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.XMLResource;
import org.eclipse.emf.ecore.xmi.impl.XMLResourceFactoryImpl;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.editor.model.IModelExporter;
import com.archimatetool.editor.utils.FileUtils;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.IFolderContainer;


/**
 * GRAFICO Model Exporter
 * GRAFICO (Git fRiendly Archi FIle COllection) is a way to persist an ArchiMate
 * model in a bunch of XML files (one file per ArchiMate element or view).
 * 
 * @author Jean-Baptiste Sarrodie
 * @author Quentin Varquet
 * @author Phillip Beauvoir
 */
public class MyExporter implements IModelExporter {
	ResourceSet resourceSet;
    
    public MyExporter() {
    }

    @Override
    public void export(IArchimateModel model) throws IOException {
    	File folder = askSaveFolder();
    	
    	if(folder == null) {
            return;
        }
    	
    	// Define target folders for model and images
    	// Delete them and re-create them (remark: FileUtils.deleteFolder() does sanity checks)
    	File modelFolder = new File(folder, "model"); //$NON-NLS-1$
    	FileUtils.deleteFolder(modelFolder);
    	modelFolder.mkdirs();
    	File imagesFolder = new File(folder, "images"); //$NON-NLS-1$
    	FileUtils.deleteFolder(imagesFolder);
    	imagesFolder.mkdirs();
    	
    	// Save model images (if any): this has to be done on original model (not a copy)
    	saveImages(model, imagesFolder);
    	
    	// Create ResourceSet
    	resourceSet = new ResourceSetImpl();
    	resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("*", new XMLResourceFactoryImpl()); //$NON-NLS-1$
    	
    	// Now work on a copy
    	// save(IArchimateModel, File) create directory structure and prepare all Resources
    	IArchimateModel copy = EcoreUtil.copy(model);
    	createResourceForFolder(copy, modelFolder);
    	
    	// Now save all Resources (done only now to generate relative URI)
    	for (Resource resource: resourceSet.getResources()) {
            resource.save(null);
    	}
    }
    
    /**
     * For each folder inside model, create a directory and a Resource to save it.
     * For each element, create a Resource to save it
     * 
     * @param folderContainer Model or folder to work on 
     * @param folder Directory in which to generate files
     * @throws IOException
     */
    private void createResourceForFolder(IFolderContainer folderContainer, File folder) throws IOException {
		// Save each children folders
    	List<IFolder> allFolders = new ArrayList<IFolder>();
    	allFolders.addAll(((IFolderContainer)folderContainer).getFolders());
		for (IFolder tmpFolder: allFolders) {
			File tmpFolderFile = new File(folder, getNameFor(tmpFolder));
			tmpFolderFile.mkdirs();
			createResource(new File(tmpFolderFile, "folder.xml"), tmpFolder); //$NON-NLS-1$
			createResourceForFolder(tmpFolder, tmpFolderFile);
		}		
		// Save each children elements
		if (folderContainer instanceof IFolder) {
    		// Save each children element
			List<EObject> allElements = new ArrayList<EObject>();
			allElements.addAll(((IFolder) folderContainer).getElements());
    		for (EObject tmpElement: allElements) {
    			createResource(new File(folder, ((IIdentifier)tmpElement).getId()+".xml"), tmpElement); //$NON-NLS-1$
    		}
		}
		if (folderContainer instanceof IArchimateModel) {
			createResource(new File(folder, "folder.xml"), folderContainer); //$NON-NLS-1$
		}
    }
    
    /**
     * Generate a proper name for directory creation
     *  
     * @param folder
     * @return
     */
    private String getNameFor(IFolder folder) {
    	return folder.getType().toString().equals("user") ? folder.getId().toString() : folder.getType().toString(); //$NON-NLS-1$
    }
    
    /**
     * Save the model to Resource
     * 
     * @param file
     * @param object
     * @throws IOException
     */
    private void createResource(File file, EObject object) throws IOException {
    	// Create a new resource for selected file and add object to persist
        XMLResource resource = (XMLResource) resourceSet.createResource(URI.createFileURI(file.getAbsolutePath()));
        resource.getDefaultSaveOptions().put(XMLResource.OPTION_ENCODING, "UTF-8"); //$NON-NLS-1$
        resource.getContents().add(object);
    }
    
    /**
     * Extract and save images used inside a model
     * This is a kind of hack and involves the following steps:
     * 1. save model in a temporary .archimate file
     * 2. check if this .archimate file is a ZIP (thus contains images) or an XML file
     * 3. if XML file, then return
     * 4. if ZIP, extract images to target folder
     * 
     * @param fModel
     * @param folder
     * @throws IOException
     */
    private void saveImages(IArchimateModel fModel, File folder) throws IOException {
    	// Step 1
    	File old = fModel.getFile();
    	File tmpFile = File.createTempFile("archi-", null); //$NON-NLS-1$
    	fModel.setFile(tmpFile);
    	IEditorModelManager.INSTANCE.saveModel(fModel);
    	
    	// Step 2
    	boolean useArchiveFormat = IArchiveManager.FACTORY.isArchiveFile(tmpFile);
    	
    	// Step 3 & 4
    	if (useArchiveFormat) {
	    	ZipFile zipFile = new ZipFile(tmpFile);
	    	for(Enumeration<? extends ZipEntry> enm = zipFile.entries(); enm.hasMoreElements();) {
	            ZipEntry zipEntry = enm.nextElement();
	            String entryName = zipEntry.getName();
	            if(entryName.startsWith("images/")) { //$NON-NLS-1$
	            	File newFile = new File(folder + File.separator + entryName.replace("images/", "")); //$NON-NLS-1$ //$NON-NLS-2$
	            	//create all non exists folders
	                new File(newFile.getParent()).mkdirs();
	                InputStream is = zipFile.getInputStream(zipEntry);
					FileOutputStream fos = new FileOutputStream(newFile);
					byte[] bytes = new byte[1024];
					int length;
					while ((length = is.read(bytes)) >= 0) {
						fos.write(bytes, 0, length);
					}
					is.close();
					fos.close();
	            }
	        }
	        zipFile.close();
    	}
    	
    	fModel.setFile(old);
    }
    
    /**
     * Ask user to select a folder. Check if it is empty and, if not, ask confirmation.
     */
    private File askSaveFolder() throws IOException {
        DirectoryDialog dialog = new DirectoryDialog(Display.getCurrent().getActiveShell());
        dialog.setText(Messages.MyExporter_0);
        dialog.setMessage(Messages.MyExporter_3);
        String path = dialog.open();
        
        if(path == null) {
            return null;
        }
        
        File folder = new File(path);
        
        if(folder.exists()) {
            String[] children = folder.list();
            if(children != null && children.length > 0) {
                boolean result = MessageDialog.openQuestion(Display.getCurrent().getActiveShell(),
                		Messages.MyExporter_0,
                        NLS.bind(Messages.MyExporter_4, folder));
                if(!result) {
                    return null;
                }
            }
        }
        
        folder.mkdirs();
        return folder;
    }
}