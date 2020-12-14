/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package golanganalyzerextension;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import ghidra.app.cmd.function.CreateFunctionCmd;
import ghidra.app.services.AbstractAnalyzer;
import ghidra.app.services.AnalyzerType;
import ghidra.app.util.importer.MessageLog;
import ghidra.framework.options.Options;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.data.StringDataType;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.util.CodeUnitInsertionException;
import ghidra.util.exception.CancelledException;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;
import ghidra.util.task.TaskMonitor;
import ghidra.program.model.lang.LanguageID;

/**
 * TODO: Provide class-level documentation that describes what this analyzer does.
 */
public class GolangAnalyzerExtensionAnalyzer extends AbstractAnalyzer {
	public GolangAnalyzerExtensionAnalyzer() {

		// TODO: Name the analyzer and give it a description.

		super("My Analyzer", "Analyzer description goes here", AnalyzerType.BYTE_ANALYZER);
	}

	@Override
	public boolean getDefaultEnablement(Program program) {

		// TODO: Return true if analyzer should be enabled by default

		return true;
	}

	@Override
	public boolean canAnalyze(Program program) {

		// TODO: Examine 'program' to determine of this analyzer should analyze it.  Return true
		// if it can.

		return true;
	}

	@Override
	public void registerOptions(Options options, Program program) {

		// TODO: If this analyzer has custom options, register them here

		options.registerOption("Option name goes here", false, null,
			"Option description goes here");
	}

	@Override
	public boolean added(Program program, AddressSetView set, TaskMonitor monitor, MessageLog log)
			throws CancelledException {

		// TODO: Perform analysis when things get added to the 'program'.  Return true if the
		// analysis succeeded.

		int pointer_size=get_pointer_size(program);

		Memory memory=program.getMemory();

		Address base=get_gopclntab(program, monitor);
		if(base==null)
		{
			log.appendMsg("gopclntab not found");
			return false;
		}
		int func_num=0;
		try {
			// magic and ...
			func_num=memory.getInt(base.add(8));
		}catch(MemoryAccessException e) {
			log.appendException(e);
			return false;
		}
		Address func_list_base=base.add(8+pointer_size);
		for(int i=0; i<func_num; i++) {
			long func_addr_value=0;
			long func_info_offset=0;
			int func_name_offset=0;
			int args=0;
			try {
				if(pointer_size==8) {
					func_addr_value=memory.getLong(func_list_base.add(i*pointer_size*2));
					func_info_offset=memory.getLong(func_list_base.add(i*pointer_size*2+pointer_size));
				}else {
					func_addr_value=memory.getInt(func_list_base.add(i*pointer_size*2));
					func_info_offset=memory.getInt(func_list_base.add(i*pointer_size*2+pointer_size));
				}
				long func_entry_value=memory.getInt(base.add(func_info_offset));
				func_name_offset=memory.getInt(base.add(func_info_offset+pointer_size));
				args=memory.getInt(base.add(func_info_offset+pointer_size+4));

				if(func_addr_value!=func_entry_value)
				{
					log.appendMsg(String.format("Wrong func addr %x %x", func_addr_value, func_entry_value));
					continue;
				}

				String func_name=create_function_name_data(program, base.add(func_name_offset));
				if(func_name==null) {
					log.appendMsg("The type of func name data is not String");
					continue;
				}

				rename_function(program, monitor, func_addr_value, func_name);
			}catch(Exception e) {
				log.appendException(e);
			}
		}

		return false;
	}

	int get_pointer_size(Program program) {
		if(program.getLanguageID().getIdAsString().contains("LE:64")) {
			return 8;
		}
		return 4;
	}

	Address get_gopclntab(Program program, TaskMonitor monitor) {
		MemoryBlock gopclntab_section=null;
		for (MemoryBlock mb : program.getMemory().getBlocks()) {
			if(mb.getName().equals(".gopclntab")) {
				gopclntab_section=mb;
			}
		}
		if(gopclntab_section!=null) {
			return gopclntab_section.getStart();
		}

		byte magic[]= {(byte)0xfb,(byte)0xff,(byte)0xff,(byte)0xff};
		Address find=null;
		while(true) {
			find=program.getMemory().findBytes(find, magic, new byte[] {(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff}, true, monitor);
			if(find==null) {
				break;
			}
			find=find.add(4);
		}

		return find;
	}

	String create_function_name_data(Program program, Address address) throws CodeUnitInsertionException {
		Listing listing=program.getListing();
		Data func_name_data=listing.getDefinedDataAt(address);
		if(func_name_data==null) {
			func_name_data=listing.createData(address, new StringDataType());
		}else if(!func_name_data.getDataType().isEquivalent((new StringDataType()))) {
			return null;
		}
		return (String)func_name_data.getValue();
	}

	void rename_function(Program program, TaskMonitor monitor, long func_addr_value, String func_name) throws DuplicateNameException, InvalidInputException {
		Address func_addr=program.getAddressFactory().getDefaultAddressSpace().getAddress(func_addr_value);
		Function func=program.getFunctionManager().getFunctionAt(func_addr);
		if(func==null) {
			CreateFunctionCmd cmd=new CreateFunctionCmd(func_name, func_addr, null, SourceType.ANALYSIS);
			cmd.applyTo(program, monitor);
			return;
		}
		func.setName(func_name, SourceType.ANALYSIS);
	}
}
