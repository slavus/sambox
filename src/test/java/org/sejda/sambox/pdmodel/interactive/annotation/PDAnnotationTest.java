/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sejda.sambox.pdmodel.interactive.annotation;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.sejda.sambox.cos.COSName;
import org.sejda.sambox.pdmodel.PDDocument;
import org.sejda.sambox.pdmodel.interactive.form.PDAcroForm;
import org.sejda.sambox.pdmodel.interactive.form.PDTextField;

/**
 * Test for the PDAnnotation classes.
 *
 */
public class PDAnnotationTest
{

    @Test
    public void createDefaultWidgetAnnotation()
    {
        PDAnnotation annotation = new PDAnnotationWidget();
        assertEquals(COSName.ANNOT, annotation.getCOSObject().getItem(COSName.TYPE));
        assertEquals(COSName.WIDGET.getName(),
                annotation.getCOSObject().getNameAsString(COSName.SUBTYPE));
    }

    @Test
    public void createWidgetAnnotationFromField()
    {
        PDDocument document = new PDDocument();
        PDAcroForm acroForm = new PDAcroForm(document);
        PDTextField textField = new PDTextField(acroForm);
        PDAnnotation annotation = textField.getWidgets().get(0);
        assertEquals(COSName.ANNOT, annotation.getCOSObject().getItem(COSName.TYPE));
        assertEquals(COSName.WIDGET.getName(),
                annotation.getCOSObject().getNameAsString(COSName.SUBTYPE));
    }

}
