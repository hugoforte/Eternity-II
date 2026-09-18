package core;

public class Coordinates
{
	public static int[][] SideFrame()
	{
		//Coordinates for board spots
		int[][] spots = new int[256][2];
		
		//Side pieces coordinates on board
		int[] corner1 = {0,0};
		int[] corner2 = {0,15};
		int[] corner3 = {15,0};
		int[] corner4 = {15,15};
		int[] sidespot1 = {1,0};
		int[] sidespot2 = {2,0};
		int[] sidespot3 = {3,0};
		int[] sidespot4 = {4,0};
		int[] sidespot5 = {5,0};
		int[] sidespot6 = {6,0};
		int[] sidespot7 = {7,0};
		int[] sidespot8 = {8,0};
		int[] sidespot9 = {9,0};
		int[] sidespot10 = {10,0};
		int[] sidespot11 = {11,0};
		int[] sidespot12 = {12,0};
		int[] sidespot13 = {13,0};
		int[] sidespot14 = {14,0};		
		int[] sidespot15 = {0,1};
		int[] sidespot16 = {0,2};
		int[] sidespot17 = {0,3};
		int[] sidespot18 = {0,4};
		int[] sidespot19 = {0,5};
		int[] sidespot20 = {0,6};
		int[] sidespot21 = {0,7};
		int[] sidespot22 = {0,8};
		int[] sidespot23 = {0,9};
		int[] sidespot24 = {0,10};
		int[] sidespot25 = {0,11};
		int[] sidespot26 = {0,12};
		int[] sidespot27 = {0,13};
		int[] sidespot28 = {0,14};		
		int[] sidespot29 = {1,15};
		int[] sidespot30 = {2,15};
		int[] sidespot31 = {3,15};
		int[] sidespot32 = {4,15};
		int[] sidespot33 = {5,15};
		int[] sidespot34 = {6,15};
		int[] sidespot35 = {7,15};
		int[] sidespot36 = {8,15};
		int[] sidespot37 = {9,15};
		int[] sidespot38 = {10,15};
		int[] sidespot39 = {11,15};
		int[] sidespot40 = {12,15};
		int[] sidespot41 = {13,15};
		int[] sidespot42 = {14,15};		
		int[] sidespot43 = {15,1};
		int[] sidespot44 = {15,2};
		int[] sidespot45 = {15,3};
		int[] sidespot46 = {15,4};
		int[] sidespot47 = {15,5};
		int[] sidespot48 = {15,6};
		int[] sidespot49 = {15,7};
		int[] sidespot50 = {15,8};
		int[] sidespot51 = {15,9};
		int[] sidespot52 = {15,10};
		int[] sidespot53 = {15,11};
		int[] sidespot54 = {15,12};
		int[] sidespot55 = {15,13};
		int[] sidespot56 = {15,14};	
		
		spots[0] = corner1;
		spots[1] = corner2;
		spots[2] = corner3;
		spots[3] = corner4;
		spots[4] = sidespot1;
		spots[5] = sidespot2;
		spots[6] = sidespot3;
		spots[7] = sidespot4;
		spots[8] = sidespot5;
		spots[9] = sidespot6;
		spots[10] = sidespot7;
		spots[11] = sidespot8;	
		spots[12] = sidespot9;   
		spots[13] = sidespot10;   
		spots[14] = sidespot11;   
		spots[15] = sidespot12;   
		spots[16] = sidespot13;   
		spots[17] = sidespot14;   
		spots[18] = sidespot15;   
		spots[19] = sidespot16;   
		spots[20] = sidespot17;   
		spots[21] = sidespot18;   
		spots[22] = sidespot19;   
		spots[23] = sidespot20;   
		spots[24] = sidespot21;   
		spots[25] = sidespot22;   
		spots[26] = sidespot23;   
		spots[27] = sidespot24;   
		spots[28] = sidespot25;   
		spots[29] = sidespot26;   
		spots[30] = sidespot27;   
		spots[31] = sidespot28;   
		spots[32] = sidespot29;   
		spots[33] = sidespot30;   
		spots[34] = sidespot31;   
		spots[35] = sidespot32;   
		spots[36] = sidespot33;   
		spots[37] = sidespot34;   
		spots[38] = sidespot35;   
		spots[39] = sidespot36;   
		spots[40] = sidespot37;   
		spots[41] = sidespot38;   
		spots[42] = sidespot39;   
		spots[43] = sidespot40;   
		spots[44] = sidespot41;   
		spots[45] = sidespot42;   
		spots[46] = sidespot43;   
		spots[47] = sidespot44;   
		spots[48] = sidespot45;   
		spots[49] = sidespot46;   
		spots[50] = sidespot47;   
		spots[51] = sidespot48;   
		spots[52] = sidespot49;   
		spots[53] = sidespot50;   
		spots[54] = sidespot51;   
		spots[55] = sidespot52;   
		spots[56] = sidespot53;
		spots[57] = sidespot54;
		spots[58] = sidespot55;
		spots[59] = sidespot56;
		
		return spots;

	}
	
	public static int[][] SecondFrame(int[][] spots)
	{		
		//Coordinates for first inner board frame
		int[] spot60 = {1,1};
		int[] spot61 = {1,2};
		int[] spot62 = {1,3};
		int[] spot63 = {1,4};
		int[] spot64 = {1,5};
		int[] spot65 = {1,6};
		int[] spot66 = {1,7};
		int[] spot67 = {1,8};
		int[] spot68 = {1,9};
		int[] spot69 = {1,10};
		int[] spot70 = {1,11};
		int[] spot71 = {1,12};
		int[] spot72 = {1,13};
		int[] spot73 = {1,14};
		int[] spot74 = {2,1};
		int[] spot75 = {3,1};
		int[] spot76 = {4,1};
		int[] spot77 = {5,1};
		int[] spot78 = {6,1};
		int[] spot79 = {7,1};
		int[] spot80 = {8,1};
		int[] spot81 = {9,1};
		int[] spot82 = {10,1};
		int[] spot83 = {11,1};
		int[] spot84 = {12,1};
		int[] spot85 = {13,1};
		int[] spot86 = {14,1};
		int[] spot87 = {14,2};
		int[] spot88 = {14,3};
		int[] spot89 = {14,4};
		int[] spot90 = {14,5};
		int[] spot91 = {14,6};
		int[] spot92 = {14,7};
		int[] spot93 = {14,8};
		int[] spot94 = {14,9};
		int[] spot95 = {14,10};
		int[] spot96 = {14,11};
		int[] spot97 = {14,12};
		int[] spot98 = {14,13};
		int[] spot99 = {14,14};
		int[] spot100 = {2,14};
		int[] spot101 = {3,14};
		int[] spot102 = {4,14};
		int[] spot103 = {5,14};
		int[] spot104 = {6,14};
		int[] spot105 = {7,14};
		int[] spot106 = {8,14};
		int[] spot107 = {9,14};
		int[] spot108 = {10,14};
		int[] spot109 = {11,14};
		int[] spot110 = {12,14};
		int[] spot111 = {13,14};
		 
		spots[60] = spot60;   
		spots[61] = spot61;   
		spots[62] = spot62;   
		spots[63] = spot63;   
		spots[64] = spot64;   
		spots[65] = spot65;   
		spots[66] = spot66;   
		spots[67] = spot67;   
		spots[68] = spot68;   
		spots[69] = spot69;   
		spots[70] = spot70;   
		spots[71] = spot71;   
		spots[72] = spot72;   
		spots[73] = spot73;   
		spots[74] = spot74;   
		spots[75] = spot75;   
		spots[76] = spot76;   
		spots[77] = spot77;   
		spots[78] = spot78;   
		spots[79] = spot79;   
		spots[80] = spot80;   
		spots[81] = spot81;   
		spots[82] = spot82;   
		spots[83] = spot83;   
		spots[84] = spot84;   
		spots[85] = spot85;   
		spots[86] = spot86;   
		spots[87] = spot87;   
		spots[88] = spot88;   
		spots[89] = spot89;   
		spots[90] = spot90;   
		spots[91] = spot91;   
		spots[92] = spot92;   
		spots[93] = spot93;   
		spots[94] = spot94;   
		spots[95] = spot95;   
		spots[96] = spot96;   
		spots[97] = spot97;   
		spots[98] = spot98;   
		spots[99] = spot99;   
		spots[100] = spot100;   
		spots[101] = spot101;   
		spots[102] = spot102;   
		spots[103] = spot103;   
		spots[104] = spot104;   
		spots[105] = spot105;   
		spots[106] = spot106;   
		spots[107] = spot107;   
		spots[108] = spot108;   
		spots[109] = spot109;   
		spots[110] = spot110;   
		spots[111] = spot111;   
		
		return spots;
	}
	
	public static int[][] ThirdFrame(int[][] spots)
	{		
		int[] spot112 = {2,2};
		int[] spot113 = {2,3};
		int[] spot114 = {2,4};
		int[] spot115 = {2,5};
		int[] spot116 = {2,6};
		int[] spot117 = {2,7};
		int[] spot118 = {2,8};
		int[] spot119 = {2,9};
		int[] spot120 = {2,10};
		int[] spot121 = {2,11};
		int[] spot122 = {2,12};
		int[] spot123 = {2,13};
		int[] spot124 = {3,2};
		int[] spot125 = {4,2};
		int[] spot126 = {5,2};
		int[] spot127 = {6,2};
		int[] spot128 = {7,2};
		int[] spot129 = {8,2};
		int[] spot130 = {9,2};
		int[] spot131 = {10,2};
		int[] spot132 = {11,2};
		int[] spot133 = {12,2};
		int[] spot134 = {13,2};
		int[] spot135 = {3,13};
		int[] spot136 = {4,13};
		int[] spot137 = {5,13};
		int[] spot138 = {6,13};
		int[] spot139 = {7,13};
		int[] spot140 = {8,13};
		int[] spot141 = {9,13};
		int[] spot142 = {10,13};
		int[] spot143 = {11,13};
		int[] spot144 = {12,13};
		int[] spot145 = {13,13};
		int[] spot146 = {13,3};
		int[] spot147 = {13,4};
		int[] spot148 = {13,5};
		int[] spot149 = {13,6};
		int[] spot150 = {13,7};
		int[] spot151 = {13,8};
		int[] spot152 = {13,9};
		int[] spot153 = {13,10};
		int[] spot154 = {13,11};
		int[] spot155 = {13,12};
		
		spots[112] = spot112;
		spots[113] = spot113;
		spots[114] = spot114;
		spots[115] = spot115;
		spots[116] = spot116;
		spots[117] = spot117;
		spots[118] = spot118;
		spots[119] = spot119;
		spots[120] = spot120;
		spots[121] = spot121;
		spots[122] = spot122;
		spots[123] = spot123;
		spots[124] = spot124;
		spots[125] = spot125;
		spots[126] = spot126;
		spots[127] = spot127;
		spots[128] = spot128;
		spots[129] = spot129;
		spots[130] = spot130;
		spots[131] = spot131;
		spots[132] = spot132;
		spots[133] = spot133;
		spots[134] = spot134;
		spots[135] = spot135;
		spots[136] = spot136;
		spots[137] = spot137;
		spots[138] = spot138;
		spots[139] = spot139;
		spots[140] = spot140;
		spots[141] = spot141;
		spots[142] = spot142;
		spots[143] = spot143;
		spots[144] = spot144;
		spots[145] = spot145;
		spots[146] = spot146;
		spots[147] = spot147;
		spots[148] = spot148;
		spots[149] = spot149;
		spots[150] = spot150;
		spots[151] = spot151;
		spots[152] = spot152;
		spots[153] = spot153;
		spots[154] = spot154;
		spots[155] = spot155;
		
		return spots;
	}
	
	public static int[][] FourthFrame(int[][] spots)
	{		
		int[] spot156 = {3,3};
		int[] spot157 = {3,4};
		int[] spot158 = {3,5};
		int[] spot159 = {3,6};
		int[] spot160 = {3,7};
		int[] spot161 = {3,8};
		int[] spot162 = {3,9};
		int[] spot163 = {3,10};
		int[] spot164 = {3,11};
		int[] spot165 = {3,12};
		int[] spot166 = {4,3};
		int[] spot167 = {5,3};
		int[] spot168 = {6,3};
		int[] spot169 = {7,3};
		int[] spot170 = {8,3};
		int[] spot171 = {9,3};
		int[] spot172 = {10,3};
		int[] spot173 = {11,3};
		int[] spot174 = {12,3};
		int[] spot175 = {12,4};
		int[] spot176 = {12,5};
		int[] spot177 = {12,6};
		int[] spot178 = {12,7};
		int[] spot179 = {12,8};
		int[] spot180 = {12,9};
		int[] spot181 = {12,10};
		int[] spot182 = {12,11};
		int[] spot183 = {12,12};
		int[] spot184 = {4,12};
		int[] spot185 = {5,12};
		int[] spot186 = {6,12};
		int[] spot187 = {7,12};
		int[] spot188 = {8,12};
		int[] spot189 = {9,12};
		int[] spot190 = {10,12};
		int[] spot191 = {11,12};		
		
		spots[156] = spot156;
		spots[157] = spot157;
		spots[158] = spot158;
		spots[159] = spot159;
		spots[160] = spot160;
		spots[161] = spot161;
		spots[162] = spot162;
		spots[163] = spot163;
		spots[164] = spot164;
		spots[165] = spot165;
		spots[166] = spot166;
		spots[167] = spot167;
		spots[168] = spot168;
		spots[169] = spot169;
		spots[170] = spot170;
		spots[171] = spot171;
		spots[172] = spot172;
		spots[173] = spot173;
		spots[174] = spot174;
		spots[175] = spot175;
		spots[176] = spot176;
		spots[177] = spot177;
		spots[178] = spot178;
		spots[179] = spot179;
		spots[180] = spot180;
		spots[181] = spot181;
		spots[182] = spot182;
		spots[183] = spot183;
		spots[184] = spot184;
		spots[185] = spot185;
		spots[186] = spot186;
		spots[187] = spot187;
		spots[188] = spot188;
		spots[189] = spot189;
		spots[190] = spot190;
		spots[191] = spot191;		
		
		return spots;
	}	
	
	public static int[][] FifthFrame(int[][] spots)
	{
		int[] spot192 = {4,4};
		int[] spot193 = {4,5};
		int[] spot194 = {4,6};
		int[] spot195 = {4,7};
		int[] spot196 = {4,8};
		int[] spot197 = {4,9};
		int[] spot198 = {4,10};
		int[] spot199 = {4,11};
		int[] spot200 = {5,4};
		int[] spot201 = {6,4};
		int[] spot202 = {7,4};
		int[] spot203 = {8,4};
		int[] spot204 = {9,4};
		int[] spot205 = {10,4};
		int[] spot206 = {11,4};
		int[] spot207 = {11,5};
		int[] spot208 = {11,6};
		int[] spot209 = {11,7};
		int[] spot210 = {11,8};
		int[] spot211 = {11,9};
		int[] spot212 = {11,10};
		int[] spot213 = {11,11};
		int[] spot214 = {5,11};
		int[] spot215 = {6,11};
		int[] spot216 = {7,11};
		int[] spot217 = {8,11};
		int[] spot218 = {9,11};
		int[] spot219 = {10,11};
		
		spots[192] = spot192;
		spots[193] = spot193;
		spots[194] = spot194;
		spots[195] = spot195;
		spots[196] = spot196;
		spots[197] = spot197;
		spots[198] = spot198;
		spots[199] = spot199;
		spots[200] = spot200;
		spots[201] = spot201;
		spots[202] = spot202;
		spots[203] = spot203;
		spots[204] = spot204;
		spots[205] = spot205;
		spots[206] = spot206;
		spots[207] = spot207;
		spots[208] = spot208;
		spots[209] = spot209;
		spots[210] = spot210;
		spots[211] = spot211;
		spots[212] = spot212;
		spots[213] = spot213;
		spots[214] = spot214;
		spots[215] = spot215;
		spots[216] = spot216;
		spots[217] = spot217;
		spots[218] = spot218;
		spots[219] = spot219;	
				
		return spots;
	}
	
	public static int[][] SixthFrame(int[][] spots)
	{
		int[] spot220 = {5,5};
		int[] spot221 = {5,6};
		int[] spot222 = {5,7};
		int[] spot223 = {5,8};
		int[] spot224 = {5,9};
		int[] spot225 = {5,10};
		int[] spot226 = {6,5};
		int[] spot227 = {7,5};
		int[] spot228 = {8,5};
		int[] spot229 = {9,5};
		int[] spot230 = {10,5};
		int[] spot231 = {10,6};
		int[] spot232 = {10,7};
		int[] spot233 = {10,8};
		int[] spot234 = {10,9};
		int[] spot235 = {10,10};
		int[] spot236 = {6,10};
		int[] spot237 = {7,10};
		int[] spot238 = {8,10};
		int[] spot239 = {9,10};
		
		spots[220] = spot220;
		spots[221] = spot221;
		spots[222] = spot222;
		spots[223] = spot223;
		spots[224] = spot224;
		spots[225] = spot225;
		spots[226] = spot226;
		spots[227] = spot227;
		spots[228] = spot228;
		spots[229] = spot229;
		spots[230] = spot230;
		spots[231] = spot231;
		spots[232] = spot232;
		spots[233] = spot233;
		spots[234] = spot234;
		spots[235] = spot235;
		spots[236] = spot236;
		spots[237] = spot237;
		spots[238] = spot238;
		spots[239] = spot239;
		
		return spots;
	}
	
	public static int[][] SeventhFrame(int[][] spots)
	{
		int[] spot240 = {6,6};
		int[] spot241 = {6,7};
		int[] spot242 = {6,8};
		int[] spot243 = {6,9};
		int[] spot244 = {7,6};
		int[] spot245 = {8,6};
		int[] spot246 = {9,6};
		int[] spot247 = {9,7};
		int[] spot248 = {9,8};
		int[] spot249 = {9,9};
		int[] spot250 = {7,9};
		int[] spot251 = {8,9};
		
		spots[240] = spot240;
		spots[241] = spot241;
		spots[242] = spot242;
		spots[243] = spot243;
		spots[244] = spot244;
		spots[245] = spot245;
		spots[246] = spot246;
		spots[247] = spot247;
		spots[248] = spot248;
		spots[249] = spot249;
		spots[250] = spot250;
		spots[251] = spot251;
		
		return spots;	
	}
	
	public static int[][] EighthFrame(int[][] spots)
	{
		int[] spot252 = {7,7};
		//Piece 139 is occupying spot 7,8 from start
		int[] spot253 = {7,8};
		int[] spot254 = {8,7};
		int[] spot255 = {8,8};
		
		spots[252] = spot252;
		spots[253] = spot253;
		spots[254] = spot254;
		spots[255] = spot255;
		
		return spots;	
	}
}