//========================================================================
//$Id: AndroidClassLoader.java 352 2010-04-01 23:56:19Z joakim.erdfelt $
//Copyright 2008 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at 
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

package org.servalproject.rhizome;

import java.io.IOException;
import java.net.URL;

import org.mortbay.jetty.webapp.WebAppContext;
import org.mortbay.log.Log;

import dalvik.system.DexClassLoader;


/**
 * AndroidClassLoader
 * 
 * Loads classes dynamically from dex files wrapped inside a zip.
 */
@SuppressWarnings("unchecked")
public class AndroidClassLoader extends ClassLoader
{
    private ClassLoader _parent;
    private ClassLoader _delegate;
    private WebAppContext _context;


    public AndroidClassLoader(String path, ClassLoader parent, WebAppContext context) throws IOException
    {
         _parent = parent;
        _context = context;
        
         if (_parent == null)
             _parent = ClassLoader.getSystemClassLoader();
   
        if (path==null || "".equals(path.trim()))
            _delegate = new DexClassLoader("", context.getTempDirectory().getCanonicalPath(),null,_parent);
        else
            _delegate = new DexClassLoader(path, context.getTempDirectory().getCanonicalPath(), null, _parent);

       
    }



    
    public synchronized URL getResource(String name)
    {
        URL url= null;
        boolean tried_parent= false;
        if (_context.isParentLoaderPriority() || isSystemPath(name))
        {
            tried_parent= true;
            
            if (_parent!=null)
                url= _parent.getResource(name);
        }

        if (url == null)
        {
            url= this.findResource(name);

            if (url == null && name.startsWith("/"))
            {
                Log.debug("HACK: leaving leading '/' off " + name);
                url= this.findResource(name.substring(1));
            }
        }

        if (url == null && !tried_parent)
        {
            if (_parent!=null)
                url= _parent.getResource(name);
        }

        Log.debug("getResource("+name+")=" + url);

        return url;
    }


    public boolean isServerPath(String name)
    {
        name=name.replace('/','.');
        while(name.startsWith("."))
            name=name.substring(1);

        String[] server_classes = _context.getServerClasses();
        if (server_classes!=null)
        {
            for (int i=0;i<server_classes.length;i++)
            {
                boolean result=true;
                String c=server_classes[i];
                if (c.startsWith("-"))
                {
                    c=c.substring(1); // TODO cache
                    result=false;
                }
                
                if (c.endsWith("."))
                {
                    if (name.startsWith(c))
                        return result;
                }
                else if (name.equals(c))
                    return result;
            }
        }
        return false;
    }

    public boolean isSystemPath(String name)
    {
        name=name.replace('/','.');
        while(name.startsWith("."))
            name=name.substring(1);
        String[] system_classes = _context.getSystemClasses();
        if (system_classes!=null)
        {
            for (int i=0;i<system_classes.length;i++)
            {
                boolean result=true;
                String c=system_classes[i];
                
                if (c.startsWith("-"))
                {
                    c=c.substring(1); // TODO cache
                    result=false;
                }
                
                if (c.endsWith("."))
                {
                    if (name.startsWith(c))
                        return result;
                }
                else if (name.equals(c))
                    return result;
            }
        }
        
        return false;
        
    }

   public synchronized Class loadClass(String name) throws ClassNotFoundException
    {
        return loadClass(name, false);
    }

    protected synchronized Class loadClass(String name, boolean resolve) throws ClassNotFoundException
    {
        Class c= findLoadedClass(name);
        ClassNotFoundException ex= null;
        boolean tried_parent= false;
        Log.debug(_context.getContextPath()+" parent priority = "+_context.isParentLoaderPriority());
        
        if (c == null && _parent!=null && (_context.isParentLoaderPriority() || isSystemPath(name)) )
        {
            tried_parent= true;
            try
            {
                Log.debug("loading class "+name+" trying parent loader first" + _parent);
                c= _parent.loadClass(name);
                Log.debug("parent loaded " + c);
            }
            catch (ClassNotFoundException e)
            {
                ex= e;
            }
        }

        if (c == null)
        {
            try
            {
                if (Log.isDebugEnabled()) Log.debug("loading class "+name+" trying delegate loader" +_delegate);
                c= _delegate.loadClass(name);
                if (Log.isDebugEnabled()) Log.debug("delegate loaded " + c);
            }
            catch (ClassNotFoundException e)
            {
                ex= e;
            }
        }

        if (c == null && _parent!=null && !tried_parent && !isServerPath(name) )
            c= _parent.loadClass(name);

        if (c == null)
            throw ex;

        if (resolve)
            resolveClass(c);

        if (Log.isDebugEnabled()) Log.debug("loaded " + c+ " from "+c.getClassLoader());
        
        return c;
    }


    @Override
    public String toString()
    {
    	return "(AndroidClassLoader, delegate=" + _delegate + ")";
    }
}
