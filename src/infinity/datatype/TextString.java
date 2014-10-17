// Near Infinity - An Infinity Engine Browser and Editor
// Copyright (C) 2001 - 2005 Jon Olav Hauglid
// See LICENSE.txt for license information

package infinity.datatype;

import infinity.util.DynamicArray;
import infinity.util.io.FileWriterNI;

import java.io.IOException;
import java.io.OutputStream;

public final class TextString extends Datatype implements InlineEditable, Readable
{
  private final byte bytes[];
  private String text;

  public TextString(byte buffer[], int offset, int length, String name)
  {
    super(offset, length, name);
    bytes = new byte[length];
    read(buffer, offset);
  }

// --------------------- Begin Interface InlineEditable ---------------------

  @Override
  public boolean update(Object value)
  {
    String newstring = (String)value;
    if (newstring.length() > getSize())
      return false;
    text = newstring;
    return true;
  }

// --------------------- End Interface InlineEditable ---------------------


// --------------------- Begin Interface Writeable ---------------------

  @Override
  public void write(OutputStream os) throws IOException
  {
    if (text == null)
      FileWriterNI.writeBytes(os, bytes);
    else
      FileWriterNI.writeString(os, text, getSize());
  }

// --------------------- End Interface Writeable ---------------------

//--------------------- Begin Interface Readable ---------------------

  @Override
  public void read(byte[] buffer, int offset)
  {
    System.arraycopy(buffer, offset, bytes, 0, getSize());
    text = null;
  }

//--------------------- End Interface Readable ---------------------

  @Override
  public String toString()
  {
    if (text == null)
      text = DynamicArray.getString(bytes, 0, bytes.length);
    return text;
  }
}

